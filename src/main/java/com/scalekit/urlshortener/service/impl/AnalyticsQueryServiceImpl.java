package com.scalekit.urlshortener.service.impl;

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
import com.scalekit.urlshortener.service.AnalyticsQueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of AnalyticsQueryService querying database and Redis structures.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsQueryServiceImpl implements AnalyticsQueryService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueryServiceImpl.class);

    private final UrlRepository urlRepository;
    private final UrlAnalyticsRepository urlAnalyticsRepository;
    private final UrlDailyStatsRepository urlDailyStatsRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public AnalyticsSummaryDto getSummary(String shortCode) {
        String cacheKey = "analytics:" + shortCode;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("Cache hit for analytics summary of short code: {}", shortCode);
                return objectMapper.readValue(cached, AnalyticsSummaryDto.class);
            }
        } catch (Exception e) {
            log.warn("Failed to read analytics summary from Redis cache: {}", e.getMessage());
        }

        // Cache miss: query database
        log.debug("Cache miss for analytics summary of short code: {}, querying DB", shortCode);
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        List<UrlAnalytics> rawAnalytics = urlAnalyticsRepository.findByShortCode(shortCode);
        List<UrlDailyStats> dailyStatsList = urlDailyStatsRepository.findByShortCode(shortCode);

        // Fetch real-time values from Redis with DB fallbacks
        long totalClicks = 0;
        long uniqueClicks = 0;
        try {
            String totalClicksStr = redisTemplate.opsForValue().get("analytics:" + shortCode + ":clicks:total");
            if (totalClicksStr != null) {
                totalClicks = Long.parseLong(totalClicksStr);
            } else {
                totalClicks = url.getClickCount();
            }

            Long uniqueClicksSize = redisTemplate.opsForSet().size("analytics:" + shortCode + ":unique_ips");
            if (uniqueClicksSize != null && uniqueClicksSize > 0) {
                uniqueClicks = uniqueClicksSize;
            } else {
                uniqueClicks = url.getUniqueClickCount();
            }
        } catch (Exception re) {
            log.warn("Redis lookup failed for summary click counts, falling back to DB: {}", re.getMessage());
            totalClicks = url.getClickCount();
            uniqueClicks = url.getUniqueClickCount();
        }

        Instant now = Instant.now();
        Instant last24h = now.minus(Duration.ofDays(1));
        Instant last7d = now.minus(Duration.ofDays(7));
        Instant last30d = now.minus(Duration.ofDays(30));

        // Historical Aggregations
        long clicksLast24Hours = rawAnalytics.stream().filter(a -> a.getClickedAt().isAfter(last24h)).count();
        long clicksLast7Days = rawAnalytics.stream().filter(a -> a.getClickedAt().isAfter(last7d)).count();
        long clicksLast30Days = rawAnalytics.stream().filter(a -> a.getClickedAt().isAfter(last30d)).count();

        // Calculate average clicks per day
        Instant firstClick = rawAnalytics.stream().map(UrlAnalytics::getClickedAt).min(Instant::compareTo).orElse(null);
        Instant lastClick = rawAnalytics.stream().map(UrlAnalytics::getClickedAt).max(Instant::compareTo).orElse(null);

        double avgClicksPerDay = 0.0;
        if (firstClick != null) {
            long daysBetween = Duration.between(firstClick, now).toDays();
            avgClicksPerDay = (double) totalClicks / Math.max(1.0, daysBetween);
        }

        // Group characteristics
        Map<String, Long> clicksByCountry = rawAnalytics.stream()
                .filter(a -> a.getCountry() != null)
                .collect(Collectors.groupingBy(UrlAnalytics::getCountry, Collectors.counting()));

        Map<String, Long> clicksByDevice = rawAnalytics.stream()
                .filter(a -> a.getDeviceType() != null)
                .collect(Collectors.groupingBy(UrlAnalytics::getDeviceType, Collectors.counting()));

        Map<String, Long> clicksByBrowser = rawAnalytics.stream()
                .filter(a -> a.getBrowser() != null)
                .collect(Collectors.groupingBy(UrlAnalytics::getBrowser, Collectors.counting()));

        Map<String, Long> clicksByOs = rawAnalytics.stream()
                .filter(a -> a.getOs() != null)
                .collect(Collectors.groupingBy(UrlAnalytics::getOs, Collectors.counting()));

        // Group last 24h by hour
        DateTimeFormatter hourFormatter = DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);
        Map<String, Long> clicksByHour = rawAnalytics.stream()
                .filter(a -> a.getClickedAt().isAfter(last24h))
                .collect(Collectors.groupingBy(a -> hourFormatter.format(a.getClickedAt()), Collectors.counting()));

        // Group last 30d by date
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
        Map<String, Long> clicksByDay = rawAnalytics.stream()
                .filter(a -> a.getClickedAt().isAfter(last30d))
                .collect(Collectors.groupingBy(a -> dateFormatter.format(a.getClickedAt()), Collectors.counting()));

        // Extract top characteristics
        String topCountry = clicksByCountry.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");

        String topDevice = clicksByDevice.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");

        String topReferrer = rawAnalytics.stream()
                .filter(a -> a.getReferrer() != null && !a.getReferrer().trim().isEmpty())
                .collect(Collectors.groupingBy(UrlAnalytics::getReferrer, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Direct");

        // Click Time-Series
        List<TimeSeriesDataPoint> clickTimeSeries = compileTimeSeries(dailyStatsList);

        AnalyticsSummaryDto summary = AnalyticsSummaryDto.builder()
                .shortCode(shortCode)
                .originalUrl(url.getOriginalUrl())
                .totalClicks(totalClicks)
                .uniqueClicks(uniqueClicks)
                .clicksLast24Hours(clicksLast24Hours)
                .clicksLast7Days(clicksLast7Days)
                .clicksLast30Days(clicksLast30Days)
                .avgClicksPerDay(avgClicksPerDay)
                .clicksByCountry(clicksByCountry)
                .clicksByDevice(clicksByDevice)
                .clicksByBrowser(clicksByBrowser)
                .clicksByOs(clicksByOs)
                .clicksByHour(clicksByHour)
                .clicksByDay(clicksByDay)
                .clickTimeSeries(clickTimeSeries)
                .topCountry(topCountry)
                .topDevice(topDevice)
                .topReferrer(topReferrer)
                .firstClick(firstClick)
                .lastClick(lastClick)
                .build();

        // Save to Redis cache (expire in 5 minutes)
        try {
            String json = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(cacheKey, json, Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Failed to cache analytics summary in Redis: {}", e.getMessage());
        }

        return summary;
    }

    @Override
    public RealTimeStatsDto getRealTimeStats(String shortCode) {
        long clicksLastMinute = 0;
        long clicksLastHour = 0;
        long totalClicksToday = 0;
        long activeVisitors = 0;

        long nowMs = System.currentTimeMillis();

        try {
            // Clicks last minute (60s)
            Long lastMinuteCount = redisTemplate.opsForZSet().count(
                    "analytics:" + shortCode + ":clicks:timestamps",
                    (double) (nowMs - 60_000),
                    (double) nowMs
            );
            if (lastMinuteCount != null) {
                clicksLastMinute = lastMinuteCount;
            }

            // Clicks last hour (3600s)
            Long lastHourCount = redisTemplate.opsForZSet().count(
                    "analytics:" + shortCode + ":clicks:timestamps",
                    (double) (nowMs - 3600_000),
                    (double) nowMs
            );
            if (lastHourCount != null) {
                clicksLastHour = lastHourCount;
            }

            // Clicks today
            String todayStr = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String todayClicksStr = redisTemplate.opsForValue().get("analytics:" + shortCode + ":clicks:today:" + todayStr);
            if (todayClicksStr != null) {
                totalClicksToday = Long.parseLong(todayClicksStr);
            }

            // Active visitors (unique IPs in last 5 minutes)
            // Prune first
            redisTemplate.opsForZSet().removeRangeByScore(
                    "analytics:" + shortCode + ":active_visitors",
                    0,
                    (double) (nowMs - 300_000)
            );
            Long visitors = redisTemplate.opsForZSet().size("analytics:" + shortCode + ":active_visitors");
            if (visitors != null) {
                activeVisitors = visitors;
            }

        } catch (Exception e) {
            log.warn("Redis operations failed while querying real-time stats: {}", e.getMessage());
        }

        return RealTimeStatsDto.builder()
                .shortCode(shortCode)
                .clicksLastMinute(clicksLastMinute)
                .clicksLastHour(clicksLastHour)
                .totalClicksToday(totalClicksToday)
                .activeVisitors(activeVisitors)
                .build();
    }

    @Override
    public List<TimeSeriesDataPoint> getClickTimeSeries(String shortCode, String range) {
        if ("24h".equalsIgnoreCase(range)) {
            // Aggregate hourly resolution from raw DB logs
            List<UrlAnalytics> analytics = urlAnalyticsRepository.findByShortCode(shortCode);
            Instant limit = Instant.now().minus(Duration.ofDays(1));
            DateTimeFormatter hourFormatter = DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

            // Group by hour
            Map<String, List<UrlAnalytics>> grouped = analytics.stream()
                    .filter(a -> a.getClickedAt().isAfter(limit))
                    .collect(Collectors.groupingBy(a -> hourFormatter.format(a.getClickedAt())));

            List<TimeSeriesDataPoint> points = new ArrayList<>();
            Instant now = Instant.now();
            for (int i = 23; i >= 0; i--) {
                Instant hourInstant = now.minus(Duration.ofHours(i));
                String hourLabel = hourFormatter.format(hourInstant);
                List<UrlAnalytics> clicksInHour = grouped.getOrDefault(hourLabel, Collections.emptyList());

                long total = clicksInHour.size();
                long unique = clicksInHour.stream().filter(UrlAnalytics::getIsUnique).count();

                points.add(TimeSeriesDataPoint.builder()
                        .label(hourLabel + ":00")
                        .totalClicks(total)
                        .uniqueClicks(unique)
                        .timestamp(hourInstant)
                        .build());
            }
            return points;
        } else {
            // Aggregate daily resolution from daily stats table
            List<UrlDailyStats> dailyStatsList = urlDailyStatsRepository.findByShortCode(shortCode);
            return compileTimeSeries(dailyStatsList);
        }
    }

    @Override
    public List<UrlResponse> getTopUrls(int limit) {
        try {
            Set<ZSetOperations.TypedTuple<String>> topCodes = redisTemplate.opsForZSet()
                    .reverseRangeWithScores("analytics:leaderboard", 0, limit - 1);

            if (topCodes != null && !topCodes.isEmpty()) {
                List<UrlResponse> responses = new ArrayList<>();
                for (ZSetOperations.TypedTuple<String> tuple : topCodes) {
                    String shortCode = tuple.getValue();
                    urlRepository.findByShortCode(shortCode).ifPresent(url -> {
                        responses.add(UrlResponse.builder()
                                .shortCode(url.getShortCode())
                                .originalUrl(url.getOriginalUrl())
                                .customAlias(url.getCustomAlias())
                                .createdAt(url.getCreatedAt())
                                .expiresAt(url.getExpiresAt())
                                .active(url.getIsActive())
                                .clickCount(tuple.getScore() != null ? tuple.getScore().longValue() : url.getClickCount())
                                .uniqueClickCount(url.getUniqueClickCount())
                                .lastAccessedAt(url.getLastAccessedAt())
                                .createdBy(url.getCreatedBy())
                                .title(url.getTitle())
                                .description(url.getDescription())
                                .expired(url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now()))
                                .build());
                    });
                }
                return responses;
            }
        } catch (Exception e) {
            log.warn("Leaderboard retrieval failed from Redis: {}", e.getMessage());
        }

        // Fallback: Query DB
        return urlRepository.findAll().stream()
                .sorted((u1, u2) -> Long.compare(u2.getClickCount(), u1.getClickCount()))
                .limit(limit)
                .map(url -> UrlResponse.builder()
                        .shortCode(url.getShortCode())
                        .originalUrl(url.getOriginalUrl())
                        .customAlias(url.getCustomAlias())
                        .createdAt(url.getCreatedAt())
                        .expiresAt(url.getExpiresAt())
                        .active(url.getIsActive())
                        .clickCount(url.getClickCount())
                        .uniqueClickCount(url.getUniqueClickCount())
                        .lastAccessedAt(url.getLastAccessedAt())
                        .createdBy(url.getCreatedBy())
                        .title(url.getTitle())
                        .description(url.getDescription())
                        .expired(url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now()))
                        .build())
                .collect(Collectors.toList());
    }

    private List<TimeSeriesDataPoint> compileTimeSeries(List<UrlDailyStats> stats) {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(29);
        LocalDate end = LocalDate.now(ZoneOffset.UTC);

        Map<LocalDate, UrlDailyStats> statsMap = stats.stream()
                .collect(Collectors.toMap(UrlDailyStats::getDate, s -> s, (s1, s2) -> s1));

        List<TimeSeriesDataPoint> points = new ArrayList<>();
        LocalDate current = start;
        while (!current.isAfter(end)) {
            UrlDailyStats stat = statsMap.get(current);
            long total = stat != null ? stat.getTotalClicks() : 0;
            long unique = stat != null ? stat.getUniqueClicks() : 0;

            points.add(TimeSeriesDataPoint.builder()
                    .label(current.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .totalClicks(total)
                    .uniqueClicks(unique)
                    .timestamp(current.atStartOfDay(ZoneOffset.UTC).toInstant())
                    .build());

            current = current.plusDays(1);
        }
        return points;
    }
}
