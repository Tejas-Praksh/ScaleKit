package com.scalekit.urlshortener.service;

import com.scalekit.common.exception.ResourceNotFoundException;
import com.scalekit.common.exception.UrlException;
import com.scalekit.common.exception.UrlExpiredException;
import com.scalekit.urlshortener.domain.Url;
import com.scalekit.urlshortener.dto.CreateUrlRequest;
import com.scalekit.urlshortener.dto.UrlResponse;
import com.scalekit.urlshortener.dto.UrlStatsResponse;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.dto.SafetyLevel;
import com.scalekit.urlshortener.repository.UrlRepository;
import com.scalekit.urlshortener.service.impl.UrlServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private CounterService counterService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UrlPasswordService urlPasswordService;

    @Mock
    private UrlSafetyService urlSafetyService;

    private UrlServiceImpl urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlServiceImpl(urlRepository, counterService, stringRedisTemplate, urlPasswordService, urlSafetyService);
        ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080/");

        lenient().when(urlSafetyService.checkUrl(anyString())).thenReturn(
                SafetyCheckResult.builder()
                        .isSafe(true)
                        .safetyLevel(SafetyLevel.SAFE)
                        .reputationScore(100)
                        .build()
        );
    }


    @Test
    void createUrl_WithSequentialGeneration_ShouldSucceed() {
        // Arrange
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://deepmind.google/technologies/gemini/")
                .title("Gemini Tech")
                .description("Google DeepMind's flagship model family")
                .build();

        long generatedId = 123456789L;
        // 123456789 in base62 padded is "008M0kX"
        String expectedShortCode = "008M0kX";

        when(counterService.getNextId()).thenReturn(generatedId);
        when(urlRepository.findByShortCode(expectedShortCode)).thenReturn(Optional.empty());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Url mockSavedUrl = Url.builder()
                .id(1L)
                .originalUrl(request.getOriginalUrl())
                .shortCode(expectedShortCode)
                .title(request.getTitle())
                .description(request.getDescription())
                .isActive(true)
                .clickCount(0L)
                .uniqueClickCount(0L)
                .createdAt(Instant.now())
                .build();

        when(urlRepository.save(any(Url.class))).thenReturn(mockSavedUrl);

        // Act
        UrlResponse response = urlService.createUrl(request);

        // Assert
        assertNotNull(response);
        assertEquals(expectedShortCode, response.getShortCode());
        assertEquals("http://localhost:8080/008M0kX", response.getShortUrl());
        assertEquals(request.getOriginalUrl(), response.getOriginalUrl());
        assertTrue(response.getActive());
        verify(counterService, times(1)).getNextId();
        verify(urlRepository, times(1)).save(any(Url.class));
        verify(valueOperations, times(1)).set(eq("url:cache:008M0kX"), eq(request.getOriginalUrl()), any());
    }

    @Test
    void createUrl_WithCustomAlias_ShouldUseAliasAsShortCode() {
        // Arrange
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://deepmind.google")
                .customAlias("deepmind")
                .build();

        when(urlRepository.findByCustomAlias("deepmind")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode("deepmind")).thenReturn(Optional.empty());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Url mockSavedUrl = Url.builder()
                .id(1L)
                .originalUrl(request.getOriginalUrl())
                .shortCode("deepmind")
                .customAlias("deepmind")
                .isActive(true)
                .clickCount(0L)
                .uniqueClickCount(0L)
                .createdAt(Instant.now())
                .build();

        when(urlRepository.save(any(Url.class))).thenReturn(mockSavedUrl);

        // Act
        UrlResponse response = urlService.createUrl(request);

        // Assert
        assertNotNull(response);
        assertEquals("deepmind", response.getShortCode());
        assertEquals("http://localhost:8080/deepmind", response.getShortUrl());
        verifyNoInteractions(counterService);
        verify(urlRepository, times(1)).save(any(Url.class));
    }

    @Test
    void createUrl_WithDuplicateCustomAlias_ShouldThrowUrlException() {
        // Arrange
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://deepmind.google")
                .customAlias("deepmind")
                .build();

        when(urlRepository.findByCustomAlias("deepmind")).thenReturn(Optional.of(new Url()));

        // Act & Assert
        assertThrows(UrlException.class, () -> urlService.createUrl(request));
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void resolveUrl_WhenCacheHits_ShouldReturnUrlWithoutQueryingDB() {
        // Arrange
        String shortCode = "abc1234";
        String expectedUrl = "https://example.com";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:cache:abc1234")).thenReturn(expectedUrl);

        // Act
        String result = urlService.resolveUrl(shortCode);

        // Assert
        assertEquals(expectedUrl, result);
        verifyNoInteractions(urlRepository);
    }

    @Test
    void resolveUrl_WhenCacheMissesAndUrlIsValid_ShouldFetchFromDBAndCacheIt() {
        // Arrange
        String shortCode = "abc1234";
        String expectedUrl = "https://example.com";
        
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:cache:abc1234")).thenReturn(null);

        Url mockUrl = Url.builder()
                .id(1L)
                .shortCode(shortCode)
                .originalUrl(expectedUrl)
                .isActive(true)
                .build();

        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(mockUrl));

        // Act
        String result = urlService.resolveUrl(shortCode);

        // Assert
        assertEquals(expectedUrl, result);
        verify(urlRepository, times(1)).findByShortCode(shortCode);
        verify(valueOperations, times(1)).set(eq("url:cache:abc1234"), eq(expectedUrl), any());
    }

    @Test
    void resolveUrl_WhenUrlIsDeleted_ShouldThrowResourceNotFoundException() {
        // Arrange
        String shortCode = "deleted";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        Url mockUrl = Url.builder()
                .id(1L)
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .isActive(false) // soft deleted
                .build();

        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(mockUrl));

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> urlService.resolveUrl(shortCode));
    }

    @Test
    void resolveUrl_WhenUrlIsExpired_ShouldEvictCacheAndThrowUrlExpiredException() {
        // Arrange
        String shortCode = "expired";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        Url mockUrl = Url.builder()
                .id(1L)
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .isActive(true)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)) // expired 1 hour ago
                .build();

        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(mockUrl));

        // Act & Assert
        assertThrows(UrlExpiredException.class, () -> urlService.resolveUrl(shortCode));
        verify(stringRedisTemplate, times(1)).delete("url:cache:expired");
    }

    @Test
    void getUrlStats_ShouldReturnAggregatedStats() {
        // Arrange
        String shortCode = "stats";
        Url mockUrl = Url.builder()
                .id(1L)
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .clickCount(100L)
                .uniqueClickCount(45L)
                .isActive(true)
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now())
                .build();

        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(mockUrl));

        // Act
        UrlStatsResponse stats = urlService.getUrlStats(shortCode);

        // Assert
        assertNotNull(stats);
        assertEquals(100L, stats.getTotalClicks());
        assertEquals(45L, stats.getUniqueClicks());
        assertTrue(stats.isActive());
        assertFalse(stats.isExpired());
    }

    @Test
    void deleteUrl_ShouldMarkInactiveAndEvictCache() {
        // Arrange
        String shortCode = "deleteMe";
        Url mockUrl = Url.builder()
                .id(1L)
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .isActive(true)
                .build();

        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(mockUrl));

        // Act
        urlService.deleteUrl(shortCode);

        // Assert
        assertFalse(mockUrl.getIsActive());
        verify(urlRepository, times(1)).save(mockUrl);
        verify(stringRedisTemplate, times(1)).delete("url:cache:deleteMe");
    }

    @Test
    void createUrl_WithMaliciousUrl_ShouldThrowUrlException() {
        // Arrange
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://malicious-site.com")
                .build();

        when(urlSafetyService.checkUrl("https://malicious-site.com")).thenReturn(
                SafetyCheckResult.builder()
                        .isSafe(false)
                        .safetyLevel(SafetyLevel.DANGEROUS)
                        .reputationScore(10)
                        .build()
        );

        // Act & Assert
        assertThrows(UrlException.class, () -> urlService.createUrl(request));
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void createUrl_WithSuspiciousUrl_ShouldSetIsSafeFalseAndStoreReputationScore() {
        // Arrange
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://suspicious-site.com")
                .build();

        when(urlSafetyService.checkUrl("https://suspicious-site.com")).thenReturn(
                SafetyCheckResult.builder()
                        .isSafe(false)
                        .safetyLevel(SafetyLevel.WARNING)
                        .reputationScore(55)
                        .build()
        );

        long generatedId = 999L;
        String expectedShortCode = "00000G7"; // padded base62

        when(counterService.getNextId()).thenReturn(generatedId);
        when(urlRepository.findByShortCode(expectedShortCode)).thenReturn(Optional.empty());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Url mockSavedUrl = Url.builder()
                .id(1L)
                .originalUrl(request.getOriginalUrl())
                .shortCode(expectedShortCode)
                .isSafe(false)
                .metadata(java.util.Map.of("reputationScore", 55))
                .isActive(true)
                .clickCount(0L)
                .uniqueClickCount(0L)
                .createdAt(Instant.now())
                .build();

        when(urlRepository.save(any(Url.class))).thenReturn(mockSavedUrl);

        // Act
        UrlResponse response = urlService.createUrl(request);

        // Assert
        assertNotNull(response);
        verify(urlRepository, times(1)).save(argThat(url -> {
            return !url.getIsSafe() && url.getMetadata() != null && Integer.valueOf(55).equals(url.getMetadata().get("reputationScore"));
        }));
    }
}

