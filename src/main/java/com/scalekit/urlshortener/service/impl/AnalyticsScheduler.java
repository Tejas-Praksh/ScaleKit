package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.domain.UrlAnalytics;
import com.scalekit.urlshortener.domain.UrlDailyStats;
import com.scalekit.urlshortener.repository.UrlAnalyticsRepository;
import com.scalekit.urlshortener.repository.UrlDailyStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scheduled component for URL Analytics background tasks.
 * Aggregates daily stats hourly and deletes raw click events older than 90 days.
 */
@Component
public class AnalyticsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsScheduler.class);

    private final UrlAnalyticsRepository urlAnalyticsRepository;
    private final UrlDailyStatsRepository urlDailyStatsRepository;

    @Value("${analytics.retention-days:90}")
    private int retentionDays;

    public AnalyticsScheduler(UrlAnalyticsRepository urlAnalyticsRepository,
                              UrlDailyStatsRepository urlDailyStatsRepository) {
        this.urlAnalyticsRepository = urlAnalyticsRepository;
        this.urlDailyStatsRepository = urlDailyStatsRepository;
    }

    /**
     * Aggregates raw hourly click events into daily stats. Runs every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void aggregateHourly() {
        log.info("Starting hourly analytics aggregation job");
        try {
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            List<UrlAnalytics> recentClicks = urlAnalyticsRepository.findByClickedAtAfter(oneHourAgo);

            if (recentClicks.isEmpty()) {
                log.info("No recent clicks found for aggregation");
                return;
            }

            // Group by shortCode
            Map<String, List<UrlAnalytics>> clicksByCode = recentClicks.stream()
                    .collect(Collectors.groupingBy(UrlAnalytics::getShortCode));

            for (Map.Entry<String, List<UrlAnalytics>> entry : clicksByCode.entrySet()) {
                String shortCode = entry.getKey();
                List<UrlAnalytics> clicks = entry.getValue();

                // Group clicks by date (UTC)
                Map<LocalDate, List<UrlAnalytics>> clicksByDate = clicks.stream()
                        .collect(Collectors.groupingBy(c -> c.getClickedAt().atZone(ZoneOffset.UTC).toLocalDate()));

                for (Map.Entry<LocalDate, List<UrlAnalytics>> dateEntry : clicksByDate.entrySet()) {
                    LocalDate date = dateEntry.getKey();
                    List<UrlAnalytics> dayClicks = dateEntry.getValue();

                    long newTotal = dayClicks.size();
                    long newUnique = dayClicks.stream().filter(UrlAnalytics::getIsUnique).count();

                    // Find top country, device, referrer from the new clicks
                    String topCountry = dayClicks.stream()
                            .filter(c -> c.getCountry() != null)
                            .collect(Collectors.groupingBy(UrlAnalytics::getCountry, Collectors.counting()))
                            .entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse("Unknown");

                    String topDevice = dayClicks.stream()
                            .filter(c -> c.getDeviceType() != null)
                            .collect(Collectors.groupingBy(UrlAnalytics::getDeviceType, Collectors.counting()))
                            .entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse("Desktop");

                    String topReferrer = dayClicks.stream()
                            .filter(c -> c.getReferrer() != null && !c.getReferrer().trim().isEmpty())
                            .collect(Collectors.groupingBy(UrlAnalytics::getReferrer, Collectors.counting()))
                            .entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse("Direct");

                    UrlDailyStats stats = urlDailyStatsRepository.findByShortCodeAndDate(shortCode, date)
                            .orElseGet(() -> UrlDailyStats.builder()
                                    .shortCode(shortCode)
                                    .date(date)
                                    .totalClicks(0L)
                                    .uniqueClicks(0L)
                                    .build());

                    stats.setTotalClicks(stats.getTotalClicks() + newTotal);
                    stats.setUniqueClicks(stats.getUniqueClicks() + newUnique);

                    if (stats.getTopCountry() == null || "Unknown".equals(stats.getTopCountry())) {
                        stats.setTopCountry(topCountry);
                    }
                    if (stats.getTopDevice() == null || "Unknown".equals(stats.getTopDevice())) {
                        stats.setTopDevice(topDevice);
                    }
                    if (stats.getTopReferrer() == null || "Direct".equals(stats.getTopReferrer())) {
                        stats.setTopReferrer(topReferrer);
                    }

                    urlDailyStatsRepository.save(stats);
                }
            }
            log.info("Completed hourly analytics aggregation job successfully");
        } catch (Exception e) {
            log.error("Error during hourly analytics aggregation: ", e);
        }
    }

    /**
     * Purges raw analytics data older than 90 days. Runs daily.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOldRecords() {
        log.info("Starting daily analytics cleanup job. Retention: {} days", retentionDays);
        try {
            Instant threshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            urlAnalyticsRepository.deleteByClickedAtBefore(threshold);
            log.info("Completed daily analytics cleanup job successfully");
        } catch (Exception e) {
            log.error("Error during analytics cleanup: ", e);
        }
    }
}
