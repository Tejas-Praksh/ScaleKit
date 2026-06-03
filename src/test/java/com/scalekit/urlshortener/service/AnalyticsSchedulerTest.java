package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.domain.UrlAnalytics;
import com.scalekit.urlshortener.domain.UrlDailyStats;
import com.scalekit.urlshortener.repository.UrlAnalyticsRepository;
import com.scalekit.urlshortener.repository.UrlDailyStatsRepository;
import com.scalekit.urlshortener.service.impl.AnalyticsScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsSchedulerTest {

    @Mock
    private UrlAnalyticsRepository urlAnalyticsRepository;

    @Mock
    private UrlDailyStatsRepository urlDailyStatsRepository;

    @InjectMocks
    private AnalyticsScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "retentionDays", 90);
    }

    @Test
    void aggregateHourly_updatesStats() {
        // Arrange
        Instant lastHour = Instant.now().minus(30, ChronoUnit.MINUTES);
        UrlAnalytics log1 = UrlAnalytics.builder()
                .shortCode("abc")
                .clickedAt(lastHour)
                .isUnique(true)
                .country("US")
                .deviceType("Desktop")
                .referrer("https://google.com")
                .build();
        UrlAnalytics log2 = UrlAnalytics.builder()
                .shortCode("abc")
                .clickedAt(lastHour)
                .isUnique(false)
                .country("US")
                .deviceType("Desktop")
                .referrer("https://google.com")
                .build();

        when(urlAnalyticsRepository.findByClickedAtAfter(any(Instant.class))).thenReturn(List.of(log1, log2));
        when(urlDailyStatsRepository.findByShortCodeAndDate(eq("abc"), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        // Act
        scheduler.aggregateHourly();

        // Assert
        ArgumentCaptor<UrlDailyStats> statsCaptor = ArgumentCaptor.forClass(UrlDailyStats.class);
        verify(urlDailyStatsRepository).save(statsCaptor.capture());
        UrlDailyStats saved = statsCaptor.getValue();
        assertEquals("abc", saved.getShortCode());
        assertEquals(2L, saved.getTotalClicks());
        assertEquals(1L, saved.getUniqueClicks());
    }

    @Test
    void cleanup_deletesOldRecords() {
        // Act
        scheduler.cleanupOldRecords();

        // Assert
        ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(urlAnalyticsRepository).deleteByClickedAtBefore(thresholdCaptor.capture());

        Instant threshold = thresholdCaptor.getValue();
        long daysDiff = ChronoUnit.DAYS.between(threshold, Instant.now());
        assertEquals(90, daysDiff);
    }

    @Test
    void cleanup_keepsRecentRecords() {
        // Arrange
        Instant now = Instant.now();
        // Since we are mocking deleteByClickedAtBefore, we want to ensure it is called with exactly
        // a threshold 90 days ago, meaning records newer than 90 days are kept.
        doNothing().when(urlAnalyticsRepository).deleteByClickedAtBefore(any(Instant.class));

        // Act
        scheduler.cleanupOldRecords();

        // Assert that delete is called with threshold, not simple clear all
        verify(urlAnalyticsRepository, never()).deleteAll();
        verify(urlAnalyticsRepository).deleteByClickedAtBefore(argThat(threshold -> 
                ChronoUnit.DAYS.between(threshold, now) >= 89 && ChronoUnit.DAYS.between(threshold, now) <= 91
        ));
    }
}
