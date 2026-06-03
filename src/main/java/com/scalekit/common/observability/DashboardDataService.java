package com.scalekit.common.observability;

import com.scalekit.common.dto.*;
import com.scalekit.common.gateway.CircuitBreakerRegistry;
import com.scalekit.common.gateway.CircuitBreakerStats;
import com.scalekit.cache.algorithm.service.ConsistentHashService;
import com.scalekit.cache.dto.CacheStats;
import com.scalekit.cache.service.LRUCacheService;
import com.scalekit.cache.service.MessageQueueService;
import com.scalekit.cache.dto.QueueStats;
import com.scalekit.cache.service.UrlDuplicateDetector;
import com.scalekit.cache.dto.UrlDuplicateStats;
import com.scalekit.urlshortener.repository.UrlAnalyticsRepository;
import com.scalekit.urlshortener.repository.UrlRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardDataService {

    private final MetricsCollector metricsCollector;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final UrlDuplicateDetector urlDuplicateDetector;
    private final MessageQueueService messageQueueService;
    private final LRUCacheService lruCacheService;
    private final ConsistentHashService consistentHashService;
    private final UrlRepository urlRepository;
    private final UrlAnalyticsRepository urlAnalyticsRepository;
    private final MeterRegistry meterRegistry;

    public DashboardSnapshot getDashboardSnapshot() {
        long startSnapshotTime = System.currentTimeMillis();
        Instant now = Instant.now();

        // 1. System Metrics
        com.sun.management.OperatingSystemMXBean osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpu = osBean.getCpuLoad();
        if (cpu < 0) cpu = 0.0;

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        double heap = (double) heapUsage.getUsed() / heapUsage.getMax();

        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        int threads = Thread.activeCount();

        double avgResponseTime = 0.0;
        try {
            var search = meterRegistry.find("scalekit.gateway.request.duration");
            double sum = 0.0;
            long count = 0;
            for (Timer t : search.timers()) {
                sum += t.totalTime(TimeUnit.MILLISECONDS);
                count += t.count();
            }
            avgResponseTime = count == 0 ? 0.0 : sum / count;
        } catch (Exception e) {
            // registry query fallback
        }

        SystemMetrics system = SystemMetrics.builder()
                .cpuUsage(Math.round(cpu * 100.0 * 100.0) / 100.0)
                .heapUsage(Math.round(heap * 100.0 * 100.0) / 100.0)
                .uptimeMs(uptime)
                .activeThreads(threads)
                .requestsPerSecond(metricsCollector.getCurrentQPS())
                .errorRate(metricsCollector.getErrorRate())
                .avgResponseTimeMs(avgResponseTime)
                .build();

        // 2. URL Shortener Metrics
        long totalUrls = 0;
        long totalClicks = 0;
        long clicksLastHour = 0;
        long activeUrls = 0;
        try {
            totalUrls = urlRepository.count();
            totalClicks = urlAnalyticsRepository.count();
            clicksLastHour = urlAnalyticsRepository.findByClickedAtAfter(now.minus(1, ChronoUnit.HOURS)).size();
            activeUrls = urlRepository.findAll().stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()) && (u.getExpiresAt() == null || u.getExpiresAt().isAfter(now)))
                    .count();
        } catch (Exception e) {
            log.warn("Failed to retrieve database metrics: {}", e.getMessage());
        }

        long hits = metricsCollector.getCacheHits();
        long misses = metricsCollector.getCacheMisses();
        long cacheTotal = hits + misses;
        double cacheHitRate = cacheTotal == 0 ? 1.0 : (double) hits / cacheTotal;

        UrlShortenerMetrics urls = UrlShortenerMetrics.builder()
                .totalUrls(totalUrls)
                .totalClicks(totalClicks)
                .clicksLastHour(clicksLastHour)
                .activeUrls(activeUrls)
                .cacheHitRate(cacheHitRate)
                .build();

        // 3. Rate Limiter Metrics
        // Dynamic values fetched via Micrometer
        long allowed = 0;
        long rejected = 0;
        try {
            allowed = (long) meterRegistry.find("scalekit.ratelimit.check").tag("allowed", "true").counters().stream()
                    .mapToDouble(c -> c.count()).sum();
            rejected = (long) meterRegistry.find("scalekit.ratelimit.check").tag("allowed", "false").counters().stream()
                    .mapToDouble(c -> c.count()).sum();
        } catch (Exception e) {}

        long totalRL = allowed + rejected;
        double rejectionRate = totalRL == 0 ? 0.0 : (double) rejected / totalRL;

        RateLimiterMetrics rateLimiter = RateLimiterMetrics.builder()
                .requestsAllowed(allowed)
                .requestsRejected(rejected)
                .rejectionRate(rejectionRate)
                .byAlgorithm(new HashMap<>())
                .byEndpoint(new HashMap<>())
                .build();

        // 4. Cache Metrics
        Map<String, CacheStats> lruStats = lruCacheService.getAllCacheStats();
        CacheMetrics cache = CacheMetrics.builder()
                .allCacheStats(lruStats)
                .overallHitRate(cacheHitRate)
                .totalEvictions(lruStats.values().stream().mapToLong(CacheStats::getEvictions).sum())
                .build();

        // 5. Consistent Hashing Metrics
        int nodeCount = consistentHashService.getAllNodes().size();
        Map<String, Long> distribution = new HashMap<>();
        double distributionScore = 1.0;
        try {
            var rebalanceReport = consistentHashService.rebalanceCheck();
            if (rebalanceReport != null) {
                distribution = rebalanceReport.getKeysPerNode();
                distributionScore = Math.max(0.0, 1.0 - rebalanceReport.getCoefficientOfVariation());
            }
        } catch (Exception e) {}

        ConsistentHashMetrics hashRing = ConsistentHashMetrics.builder()
                .nodeCount(nodeCount)
                .distribution(distribution)
                .distributionScore(distributionScore)
                .build();

        // 6. Bloom Filter Metrics
        int totalFilters = 1;
        long totalInserted = 0;
        double avgFillRatio = 0.0;
        double bloomFpr = 0.0;
        UrlDuplicateStats bloomStats = urlDuplicateDetector.getStats();
        if (bloomStats != null) {
            totalInserted = bloomStats.getUrlsAdded();
            bloomFpr = bloomStats.getEstimatedFPR();
            if (bloomStats.getFilterStats() != null) {
                avgFillRatio = bloomStats.getFilterStats().getCurrentFillRatio();
            }
        }

        BloomFilterMetrics bloomFilter = BloomFilterMetrics.builder()
                .totalFilters(totalFilters)
                .totalInserted(totalInserted)
                .avgFillRatio(avgFillRatio)
                .estimatedFPR(bloomFpr)
                .build();

        // 7. Queue Metrics
        Map<String, QueueStats> queues = messageQueueService.getAllStats();
        long totalPending = queues.values().stream().mapToLong(QueueStats::getCurrentSize).sum();
        long totalDlq = queues.values().stream().mapToLong(QueueStats::getCurrentDLQSize).sum();

        QueueMetrics queue = QueueMetrics.builder()
                .queues(queues)
                .totalPending(totalPending)
                .totalDLQ(totalDlq)
                .build();

        // 8. Evaluate Alerts
        List<AlertDto> activeAlerts = new ArrayList<>();
        
        // Cache hit rate < 50%: WARNING
        if (cacheTotal > 0 && cacheHitRate < 0.5) {
            activeAlerts.add(AlertDto.builder()
                    .type("WARNING")
                    .component("cache")
                    .message("Cache hit rate is critically low: " + Math.round(cacheHitRate * 100) + "%")
                    .detectedAt(now)
                    .build());
        }

        // Bloom filter > 80% full: WARNING
        if (avgFillRatio > 0.8) {
            activeAlerts.add(AlertDto.builder()
                    .type("WARNING")
                    .component("bloomFilter")
                    .message("Bloom filter is over 80% full: " + Math.round(avgFillRatio * 100) + "%")
                    .detectedAt(now)
                    .build());
        }

        // Circuit breaker OPEN: CRITICAL
        Map<String, CircuitBreakerStats> cbStats = circuitBreakerRegistry.getAllStats();
        cbStats.forEach((name, cb) -> {
            if ("OPEN".equals(cb.getState())) {
                activeAlerts.add(AlertDto.builder()
                        .type("CRITICAL")
                        .component("circuitBreaker")
                        .message("Circuit breaker is OPEN for route: " + name)
                        .detectedAt(now)
                        .build());
            }
        });

        // Queue DLQ > 100: WARNING
        if (totalDlq > 100) {
            activeAlerts.add(AlertDto.builder()
                    .type("WARNING")
                    .component("queue")
                    .message("Queue DLQ count has exceeded 100. Count: " + totalDlq)
                    .detectedAt(now)
                    .build());
        }

        // Error rate > 5%: CRITICAL
        double errorRate = metricsCollector.getErrorRate();
        if (errorRate > 0.05) {
            activeAlerts.add(AlertDto.builder()
                    .type("CRITICAL")
                    .component("system")
                    .message("System error rate has exceeded 5%: " + Math.round(errorRate * 100) + "%")
                    .detectedAt(now)
                    .build());
        }

        // Response time > 1s (1000ms): WARNING
        if (avgResponseTime > 1000.0) {
            activeAlerts.add(AlertDto.builder()
                    .type("WARNING")
                    .component("system")
                    .message("Average response time has exceeded 1 second: " + Math.round(avgResponseTime) + "ms")
                    .detectedAt(now)
                    .build());
        }

        return DashboardSnapshot.builder()
                .timestamp(now)
                .system(system)
                .urls(urls)
                .rateLimiter(rateLimiter)
                .cache(cache)
                .hashRing(hashRing)
                .bloomFilter(bloomFilter)
                .queue(queue)
                .activeAlerts(activeAlerts)
                .build();
    }
}
