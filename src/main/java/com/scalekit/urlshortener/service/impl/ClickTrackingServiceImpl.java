package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.domain.UrlAnalytics;
import com.scalekit.urlshortener.domain.UrlDailyStats;
import com.scalekit.urlshortener.dto.GeoLocationDetails;
import com.scalekit.urlshortener.dto.UserAgentDetails;
import com.scalekit.urlshortener.repository.UrlAnalyticsRepository;
import com.scalekit.urlshortener.repository.UrlDailyStatsRepository;
import com.scalekit.urlshortener.repository.UrlRepository;
import com.scalekit.urlshortener.service.ClickTrackingService;
import com.scalekit.urlshortener.service.GeoIpService;
import com.scalekit.urlshortener.service.UserAgentParserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Asynchronous service to track clicks and process analytics out of the critical path.
 */
@Service
@RequiredArgsConstructor
public class ClickTrackingServiceImpl implements ClickTrackingService {

    private static final Logger log = LoggerFactory.getLogger(ClickTrackingServiceImpl.class);

    private final UrlRepository urlRepository;
    private final UrlAnalyticsRepository urlAnalyticsRepository;
    private final UrlDailyStatsRepository urlDailyStatsRepository;
    private final UserAgentParserService userAgentParserService;
    private final GeoIpService geoIpService;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Async("analyticsExecutor")
    @Transactional
    public void trackClick(String shortCode, String ipAddress, String referrer, String userAgent) {
        long startTime = System.currentTimeMillis();
        try {
            log.debug("Async click tracking started for short code: {}", shortCode);

            // 1. Enrich metadata using new parsing services
            UserAgentDetails uaDetails = userAgentParserService.parse(userAgent);
            GeoLocationDetails geoDetails = geoIpService.lookup(ipAddress);

            String os = uaDetails.getOs();
            String browser = uaDetails.getBrowser();
            String deviceType = uaDetails.getDeviceType();
            String country = geoDetails.getCountry();
            String city = geoDetails.getCity();

            // 2. Uniqueness check & Redis updates
            boolean isUnique = false;
            long nowMs = System.currentTimeMillis();

            try {
                // Check uniqueness in Redis Set
                String uniqueIpsKey = "analytics:" + shortCode + ":unique_ips";
                Long added = redisTemplate.opsForSet().add(uniqueIpsKey, ipAddress != null ? ipAddress : "unknown-ip");
                isUnique = (added != null && added > 0);

                // Increment Redis total clicks counter
                redisTemplate.opsForValue().increment("analytics:" + shortCode + ":clicks:total");

                // Update Leaderboard (ZSET)
                redisTemplate.opsForZSet().incrementScore("analytics:leaderboard", shortCode, 1.0);

                // Add to Sliding Window Timestamps ZSET (last 1 hour window)
                String clickId = UUID.randomUUID().toString();
                redisTemplate.opsForZSet().add("analytics:" + shortCode + ":clicks:timestamps", clickId, (double) nowMs);
                redisTemplate.opsForZSet().removeRangeByScore("analytics:" + shortCode + ":clicks:timestamps", 0, (double) (nowMs - 3600_000));
                redisTemplate.expire("analytics:" + shortCode + ":clicks:timestamps", Duration.ofHours(2));

                // Add to Active Visitors ZSET (last 5 minutes window)
                if (ipAddress != null) {
                    redisTemplate.opsForZSet().add("analytics:" + shortCode + ":active_visitors", ipAddress, (double) nowMs);
                    redisTemplate.opsForZSet().removeRangeByScore("analytics:" + shortCode + ":active_visitors", 0, (double) (nowMs - 300_000));
                    redisTemplate.expire("analytics:" + shortCode + ":active_visitors", Duration.ofMinutes(10));
                }

                // Increment clicks today counter
                String todayStr = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String todayKey = "analytics:" + shortCode + ":clicks:today:" + todayStr;
                redisTemplate.opsForValue().increment(todayKey);
                redisTemplate.expire(todayKey, Duration.ofDays(2));

            } catch (Exception re) {
                log.warn("Redis operations failed during analytics tracking, falling back to DB: {}", re.getMessage());
                // Fallback uniqueness check from DB
                if (ipAddress != null) {
                    isUnique = !urlAnalyticsRepository.existsByShortCodeAndIpAddress(shortCode, ipAddress);
                } else {
                    isUnique = false;
                }
            }

            // 3. Increment core counters in DB using modifying queries to prevent optimistic lock exceptions
            Instant clickedAtInstant = Instant.ofEpochMilli(nowMs);
            int updatedRows;
            if (isUnique) {
                updatedRows = urlRepository.incrementClickAndUniqueCount(shortCode, clickedAtInstant);
            } else {
                updatedRows = urlRepository.incrementClickCount(shortCode, clickedAtInstant);
            }

            if (updatedRows == 0) {
                log.warn("Attempted to track click for non-existent short code: {}", shortCode);
                return;
            }

            // 4. Save detailed analytics event to DB
            UrlAnalytics analytics = UrlAnalytics.builder()
                    .shortCode(shortCode)
                    .ipAddress(ipAddress)
                    .referrer(referrer)
                    .userAgent(userAgent)
                    .os(os)
                    .browser(browser)
                    .deviceType(deviceType)
                    .country(country)
                    .city(city)
                    .isUnique(isUnique)
                    .clickedAt(clickedAtInstant)
                    .responseTimeMs((int) (System.currentTimeMillis() - startTime))
                    .build();

            urlAnalyticsRepository.save(analytics);

            // 5. Update Daily Stats pre-aggregations
            updateDailyStats(shortCode, isUnique, country, deviceType, referrer);

            log.debug("Async click tracking completed successfully for short code: {} in {} ms",
                    shortCode, (System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            log.error("Error occurred during async click tracking for short code: " + shortCode, e);
        }
    }

    private void updateDailyStats(String shortCode, boolean isUnique, String country, String deviceType, String referrer) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            UrlDailyStats stats = urlDailyStatsRepository.findByShortCodeAndDate(shortCode, today)
                    .orElseGet(() -> UrlDailyStats.builder()
                            .shortCode(shortCode)
                            .date(today)
                            .totalClicks(0L)
                            .uniqueClicks(0L)
                            .build());

            stats.setTotalClicks(stats.getTotalClicks() + 1);
            if (isUnique) {
                stats.setUniqueClicks(stats.getUniqueClicks() + 1);
            }

            // Update top characteristics simply (keep whatever was set or overwrite for simplicity)
            if (stats.getTopCountry() == null || "Unknown".equals(stats.getTopCountry())) {
                stats.setTopCountry(country);
            }
            if (stats.getTopDevice() == null) {
                stats.setTopDevice(deviceType);
            }
            if (stats.getTopReferrer() == null && referrer != null) {
                stats.setTopReferrer(referrer);
            }

            urlDailyStatsRepository.save(stats);
        } catch (Exception e) {
            log.warn("Failed to update daily stats for short code: {}, reason: {}", shortCode, e.getMessage());
        }
    }
}
