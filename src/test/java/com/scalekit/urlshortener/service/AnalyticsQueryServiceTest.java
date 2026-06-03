package com.scalekit.urlshortener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.exception.ResourceNotFoundException;
import com.scalekit.urlshortener.domain.Url;
import com.scalekit.urlshortener.domain.UrlAnalytics;
import com.scalekit.urlshortener.domain.UrlDailyStats;
import com.scalekit.urlshortener.dto.AnalyticsSummaryDto;
import com.scalekit.urlshortener.dto.RealTimeStatsDto;
import com.scalekit.urlshortener.dto.TimeSeriesDataPoint;
import com.scalekit.urlshortener.dto.UrlResponse;
import com.scalekit.urlshortener.repository.UrlAnalyticsRepository;
import com.scalekit.urlshortener.repository.UrlDailyStatsRepository;
import com.scalekit.urlshortener.repository.UrlRepository;
import com.scalekit.urlshortener.service.impl.AnalyticsQueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsQueryServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UrlAnalyticsRepository urlAnalyticsRepository;

    @Mock
    private UrlDailyStatsRepository urlDailyStatsRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private ObjectMapper objectMapper;

    private AnalyticsQueryServiceImpl analyticsQueryService;

    private final String shortCode = "test123";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        analyticsQueryService = new AnalyticsQueryServiceImpl(
                urlRepository,
                urlAnalyticsRepository,
                urlDailyStatsRepository,
                redisTemplate,
                objectMapper
        );

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void getSummary_cacheHit_returnsCached() throws Exception {
        // Arrange
        AnalyticsSummaryDto cachedDto = AnalyticsSummaryDto.builder()
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .totalClicks(100)
                .uniqueClicks(50)
                .build();
        String json = objectMapper.writeValueAsString(cachedDto);

        when(valueOperations.get("analytics:" + shortCode)).thenReturn(json);

        // Act
        AnalyticsSummaryDto result = analyticsQueryService.getSummary(shortCode);

        // Assert
        assertNotNull(result);
        assertEquals(shortCode, result.getShortCode());
        assertEquals("https://example.com", result.getOriginalUrl());
        assertEquals(100, result.getTotalClicks());
        assertEquals(50, result.getUniqueClicks());

        verifyNoInteractions(urlRepository);
        verifyNoInteractions(urlAnalyticsRepository);
    }

    @Test
    void getSummary_cacheMiss_queriesAndCaches() throws Exception {
        // Arrange
        Url url = Url.builder()
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .clickCount(10L)
                .uniqueClickCount(5L)
                .build();

        UrlAnalytics analytics1 = UrlAnalytics.builder()
                .shortCode(shortCode)
                .clickedAt(Instant.now().minusSeconds(3600)) // 1 hour ago
                .ipAddress("8.8.8.8")
                .country("United States")
                .city("Mountain View")
                .deviceType("Desktop")
                .browser("Chrome")
                .os("Windows")
                .referrer("https://google.com")
                .isUnique(true)
                .build();

        UrlDailyStats dailyStat = UrlDailyStats.builder()
                .shortCode(shortCode)
                .date(LocalDate.now())
                .totalClicks(10L)
                .uniqueClicks(5L)
                .topCountry("United States")
                .topDevice("Desktop")
                .topReferrer("https://google.com")
                .build();

        when(valueOperations.get("analytics:" + shortCode)).thenReturn(null);
        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(url));
        when(urlAnalyticsRepository.findByShortCode(shortCode)).thenReturn(List.of(analytics1));
        when(urlDailyStatsRepository.findByShortCode(shortCode)).thenReturn(List.of(dailyStat));

        when(valueOperations.get("analytics:" + shortCode + ":clicks:total")).thenReturn("12");
        when(setOperations.size("analytics:" + shortCode + ":unique_ips")).thenReturn(6L);

        // Act
        AnalyticsSummaryDto result = analyticsQueryService.getSummary(shortCode);

        // Assert
        assertNotNull(result);
        assertEquals(shortCode, result.getShortCode());
        assertEquals("https://example.com", result.getOriginalUrl());
        assertEquals(12, result.getTotalClicks()); // real-time from Redis
        assertEquals(6, result.getUniqueClicks());  // real-time from Redis Set size
        assertEquals("United States", result.getTopCountry());

        verify(valueOperations).set(eq("analytics:" + shortCode), anyString(), any(java.time.Duration.class));
    }

    @Test
    void getRealTimeStats_alwaysFresh() {
        // Arrange
        when(zSetOperations.count(eq("analytics:" + shortCode + ":clicks:timestamps"), anyDouble(), anyDouble()))
                .thenReturn(5L) // last minute
                .thenReturn(15L); // last hour
        when(valueOperations.get(contains("analytics:" + shortCode + ":clicks:today:"))).thenReturn("45");
        when(zSetOperations.size("analytics:" + shortCode + ":active_visitors")).thenReturn(8L);

        // Act
        RealTimeStatsDto result = analyticsQueryService.getRealTimeStats(shortCode);

        // Assert
        assertNotNull(result);
        assertEquals(shortCode, result.getShortCode());
        assertEquals(5, result.getClicksLastMinute());
        assertEquals(15, result.getClicksLastHour());
        assertEquals(45, result.getTotalClicksToday());
        assertEquals(8, result.getActiveVisitors());
    }

    @Test
    void getClickTimeSeries_correctDateRange() {
        // Arrange
        UrlDailyStats dailyStat = UrlDailyStats.builder()
                .shortCode(shortCode)
                .date(LocalDate.now().minusDays(1))
                .totalClicks(10L)
                .uniqueClicks(5L)
                .build();

        when(urlDailyStatsRepository.findByShortCode(shortCode)).thenReturn(List.of(dailyStat));

        // Act
        List<TimeSeriesDataPoint> result = analyticsQueryService.getClickTimeSeries(shortCode, "30d");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        TimeSeriesDataPoint point = result.stream()
                .filter(p -> p.getLabel().equals(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Yesterday's data point not found"));
        assertEquals(10, point.getTotalClicks());
        assertEquals(5, point.getUniqueClicks());
    }

    @Test
    void getTopUrls_returnsTopN() {
        // Arrange
        Set<ZSetOperations.TypedTuple<String>> topMembers = new LinkedHashSet<>();
        topMembers.add(new DefaultTypedTuple<>("url1", 100.0));
        topMembers.add(new DefaultTypedTuple<>("url2", 50.0));

        when(zSetOperations.reverseRangeWithScores("analytics:leaderboard", 0, 1)).thenReturn(topMembers);

        Url u1 = Url.builder().shortCode("url1").originalUrl("https://u1.com").clickCount(100L).build();
        Url u2 = Url.builder().shortCode("url2").originalUrl("https://u2.com").clickCount(50L).build();

        when(urlRepository.findByShortCode("url1")).thenReturn(Optional.of(u1));
        when(urlRepository.findByShortCode("url2")).thenReturn(Optional.of(u2));

        // Act
        List<UrlResponse> result = analyticsQueryService.getTopUrls(2);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("url1", result.get(0).getShortCode());
        assertEquals("url2", result.get(1).getShortCode());
    }
}
