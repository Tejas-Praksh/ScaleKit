package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.domain.UrlAnalytics;
import com.scalekit.urlshortener.domain.UrlDailyStats;
import com.scalekit.urlshortener.dto.GeoLocationDetails;
import com.scalekit.urlshortener.dto.UserAgentDetails;
import com.scalekit.urlshortener.repository.UrlAnalyticsRepository;
import com.scalekit.urlshortener.repository.UrlDailyStatsRepository;
import com.scalekit.urlshortener.repository.UrlRepository;
import com.scalekit.urlshortener.service.impl.ClickTrackingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickTrackingServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UrlAnalyticsRepository urlAnalyticsRepository;

    @Mock
    private UrlDailyStatsRepository urlDailyStatsRepository;

    @Mock
    private UserAgentParserService userAgentParserService;

    @Mock
    private GeoIpService geoIpService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private ClickTrackingServiceImpl clickTrackingService;

    private final String shortCode = "abcdefg";
    private final String ipAddress = "8.8.8.8";
    private final String referrer = "https://google.com";
    private final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120";

    @BeforeEach
    void setUp() {
        // Setup Redis Operations mocks
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void processClick_savesAnalytics() {
        // Arrange
        when(userAgentParserService.parse(userAgent)).thenReturn(
                new UserAgentDetails("Windows", "Chrome", "Desktop")
        );
        when(geoIpService.lookup(ipAddress)).thenReturn(
                new GeoLocationDetails("United States", "Mountain View")
        );
        when(setOperations.add(anyString(), anyString())).thenReturn(1L); // unique IP
        when(urlRepository.incrementClickAndUniqueCount(eq(shortCode), any(Instant.class))).thenReturn(1);
        when(urlDailyStatsRepository.findByShortCodeAndDate(eq(shortCode), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        // Act
        clickTrackingService.trackClick(shortCode, ipAddress, referrer, userAgent);

        // Assert
        ArgumentCaptor<UrlAnalytics> analyticsCaptor = ArgumentCaptor.forClass(UrlAnalytics.class);
        verify(urlAnalyticsRepository).save(analyticsCaptor.capture());
        UrlAnalytics saved = analyticsCaptor.getValue();

        assertEquals(shortCode, saved.getShortCode());
        assertEquals(ipAddress, saved.getIpAddress());
        assertEquals("Windows", saved.getOs());
        assertEquals("Chrome", saved.getBrowser());
        assertEquals("Desktop", saved.getDeviceType());
        assertEquals("United States", saved.getCountry());
        assertEquals("Mountain View", saved.getCity());
        assertTrue(saved.getIsUnique());
        assertNotNull(saved.getClickedAt());
    }

    @Test
    void processClick_uniqueIp_marksUnique() {
        // Arrange
        when(userAgentParserService.parse(userAgent)).thenReturn(
                new UserAgentDetails("Windows", "Chrome", "Desktop")
        );
        when(geoIpService.lookup(ipAddress)).thenReturn(
                new GeoLocationDetails("United States", "Mountain View")
        );
        // SADD returns 1L -> New element added, so unique
        when(setOperations.add(eq("analytics:" + shortCode + ":unique_ips"), eq(ipAddress))).thenReturn(1L);
        when(urlRepository.incrementClickAndUniqueCount(eq(shortCode), any(Instant.class))).thenReturn(1);

        // Act
        clickTrackingService.trackClick(shortCode, ipAddress, referrer, userAgent);

        // Assert
        ArgumentCaptor<UrlAnalytics> analyticsCaptor = ArgumentCaptor.forClass(UrlAnalytics.class);
        verify(urlAnalyticsRepository).save(analyticsCaptor.capture());
        assertTrue(analyticsCaptor.getValue().getIsUnique());
    }

    @Test
    void processClick_sameIp_marksNotUnique() {
        // Arrange
        when(userAgentParserService.parse(userAgent)).thenReturn(
                new UserAgentDetails("Windows", "Chrome", "Desktop")
        );
        when(geoIpService.lookup(ipAddress)).thenReturn(
                new GeoLocationDetails("United States", "Mountain View")
        );
        // SADD returns 0L -> Element already exists in set, so not unique
        when(setOperations.add(eq("analytics:" + shortCode + ":unique_ips"), eq(ipAddress))).thenReturn(0L);
        when(urlRepository.incrementClickCount(eq(shortCode), any(Instant.class))).thenReturn(1);

        // Act
        clickTrackingService.trackClick(shortCode, ipAddress, referrer, userAgent);

        // Assert
        ArgumentCaptor<UrlAnalytics> analyticsCaptor = ArgumentCaptor.forClass(UrlAnalytics.class);
        verify(urlAnalyticsRepository).save(analyticsCaptor.capture());
        assertFalse(analyticsCaptor.getValue().getIsUnique());
    }

    @Test
    void processClick_updatesRedisCounters() {
        // Arrange
        when(userAgentParserService.parse(userAgent)).thenReturn(
                new UserAgentDetails("Windows", "Chrome", "Desktop")
        );
        when(geoIpService.lookup(ipAddress)).thenReturn(
                new GeoLocationDetails("United States", "Mountain View")
        );
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        when(urlRepository.incrementClickAndUniqueCount(eq(shortCode), any(Instant.class))).thenReturn(1);

        // Act
        clickTrackingService.trackClick(shortCode, ipAddress, referrer, userAgent);

        // Assert Redis updates
        verify(valueOperations).increment(eq("analytics:" + shortCode + ":clicks:total"));
        verify(zSetOperations).incrementScore(eq("analytics:leaderboard"), eq(shortCode), eq(1.0));
        verify(zSetOperations).add(eq("analytics:" + shortCode + ":clicks:timestamps"), anyString(), anyDouble());
        verify(zSetOperations).add(eq("analytics:" + shortCode + ":active_visitors"), eq(ipAddress), anyDouble());
    }

    @Test
    void processClick_dbFailure_logsError() {
        // Arrange
        when(userAgentParserService.parse(userAgent)).thenReturn(
                new UserAgentDetails("Windows", "Chrome", "Desktop")
        );
        when(geoIpService.lookup(ipAddress)).thenReturn(
                new GeoLocationDetails("United States", "Mountain View")
        );
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        // Throw an exception when trying to update DB
        when(urlRepository.incrementClickAndUniqueCount(eq(shortCode), any(Instant.class)))
                .thenThrow(new RuntimeException("Database down"));

        // Act & Assert
        // The exception should be caught and not propagate to the caller
        assertDoesNotThrow(() -> clickTrackingService.trackClick(shortCode, ipAddress, referrer, userAgent));
    }
}
