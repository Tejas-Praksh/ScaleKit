package com.scalekit.ratelimiter.service;

import com.scalekit.analytics.domain.AlgorithmBenchmark;
import com.scalekit.analytics.repository.AlgorithmBenchmarkRepository;
import com.scalekit.ratelimiter.algorithm.*;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import com.scalekit.ratelimiter.dto.SystemHealthDto;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Complete benchmarking service that runs the same workload across all three
 * rate limiting algorithms and collects comparative metrics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompleteBenchmarkService {

    private final TokenBucketAlgorithm tokenBucketAlgorithm;
    private final SlidingWindowAlgorithm slidingWindowAlgorithm;
    private final FixedWindowAlgorithm fixedWindowAlgorithm;
    private final AdaptiveRateLimiter adaptiveRateLimiter;
    private final RateLimitRules rules;
    private final MeterRegistry meterRegistry;
    private final AlgorithmBenchmarkRepository benchmarkRepository;

    /**
     * Runs a complete benchmark across all algorithms with the given parameters.
     *
     * @param identifier     the client identifier to test with
     * @param endpoint       the endpoint name for rule lookup
     * @param requestCount   number of requests to send per algorithm
     * @return {@link FullBenchmarkResult} with per-algorithm results
     */
    public FullBenchmarkResult runFullBenchmark(String identifier, String endpoint, int requestCount) {
        log.info("Starting full benchmark: identifier={} endpoint={} requests={}", identifier, endpoint, requestCount);

        RateLimitRules.EndpointRule rule = resolveRule(endpoint);
        int limit = (rule != null) ? rule.getRequestsPerMinute() : 100;

        // --- Token Bucket ---
        AlgorithmBenchmarkResult tbResult = benchmarkTokenBucket(identifier, limit, requestCount);

        // --- Sliding Window ---
        AlgorithmBenchmarkResult swResult = benchmarkSlidingWindow(identifier, limit, requestCount);

        // --- Fixed Window ---
        AlgorithmBenchmarkResult fwResult = benchmarkFixedWindow(identifier, limit, requestCount);

        // --- Adaptive ---
        AlgorithmBenchmarkResult adResult = benchmarkAdaptive(identifier, limit, requestCount);

        // Save results to database
        saveBenchmarkToDb(tbResult);
        saveBenchmarkToDb(swResult);
        saveBenchmarkToDb(fwResult);
        saveBenchmarkToDb(adResult);

        // Build recommendation
        String recommendation = buildRecommendation(tbResult, swResult, fwResult, adResult);


        return FullBenchmarkResult.builder()
                .identifier(identifier)
                .endpoint(endpoint)
                .requestCount(requestCount)
                .limit(limit)
                .tokenBucket(tbResult)
                .slidingWindow(swResult)
                .fixedWindow(fwResult)
                .adaptive(adResult)
                .recommendation(recommendation)
                .timestamp(Instant.now())
                .build();
    }

    private AlgorithmBenchmarkResult benchmarkTokenBucket(String identifier, int limit, int requestCount) {
        String key = "bench:tb:" + identifier + ":" + UUID.randomUUID();
        double capacity = limit;
        double refillRate = limit / 60.0;

        long totalNanos = 0;
        int allowed = 0;
        int rejected = 0;
        long minLatency = Long.MAX_VALUE;
        long maxLatency = 0;

        for (int i = 0; i < requestCount; i++) {
            long start = System.nanoTime();
            TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, capacity, refillRate);
            long elapsed = System.nanoTime() - start;
            totalNanos += elapsed;
            minLatency = Math.min(minLatency, elapsed);
            maxLatency = Math.max(maxLatency, elapsed);
            if (result.isAllowed()) allowed++;
            else rejected++;
        }

        long memoryBytes = tokenBucketAlgorithm.getMemoryUsage(key);
        tokenBucketAlgorithm.resetBucket(key);

        return AlgorithmBenchmarkResult.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .totalRequests(requestCount)
                .allowedRequests(allowed)
                .rejectedRequests(rejected)
                .totalTimeNanos(totalNanos)
                .avgLatencyNanos(totalNanos / requestCount)
                .minLatencyNanos(minLatency)
                .maxLatencyNanos(maxLatency)
                .memoryBytesPerKey(memoryBytes)
                .complexity("O(1) - Hash with 2 fields")
                .burstBehavior("Allows bursts up to capacity, then throttles")
                .build();
    }

    private AlgorithmBenchmarkResult benchmarkSlidingWindow(String identifier, int limit, int requestCount) {
        String key = "bench:sw:" + identifier + ":" + UUID.randomUUID();
        long windowMs = 60000L;

        long totalNanos = 0;
        int allowed = 0;
        int rejected = 0;
        long minLatency = Long.MAX_VALUE;
        long maxLatency = 0;

        for (int i = 0; i < requestCount; i++) {
            long start = System.nanoTime();
            SlidingWindowResult result = slidingWindowAlgorithm.tryConsume(key, limit, windowMs);
            long elapsed = System.nanoTime() - start;
            totalNanos += elapsed;
            minLatency = Math.min(minLatency, elapsed);
            maxLatency = Math.max(maxLatency, elapsed);
            if (result.isAllowed()) allowed++;
            else rejected++;
        }

        long memoryBytes = slidingWindowAlgorithm.getMemoryUsage(key);
        slidingWindowAlgorithm.resetWindow(key);

        return AlgorithmBenchmarkResult.builder()
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .totalRequests(requestCount)
                .allowedRequests(allowed)
                .rejectedRequests(rejected)
                .totalTimeNanos(totalNanos)
                .avgLatencyNanos(totalNanos / requestCount)
                .minLatencyNanos(minLatency)
                .maxLatencyNanos(maxLatency)
                .memoryBytesPerKey(memoryBytes)
                .complexity("O(log N) - Sorted Set with N members per window")
                .burstBehavior("No bursts, strict rolling window")
                .build();
    }

    private AlgorithmBenchmarkResult benchmarkFixedWindow(String identifier, int limit, int requestCount) {
        String key = "bench:fw:" + identifier + ":" + UUID.randomUUID();
        int windowSeconds = 60;

        long totalNanos = 0;
        int allowed = 0;
        int rejected = 0;
        long minLatency = Long.MAX_VALUE;
        long maxLatency = 0;

        for (int i = 0; i < requestCount; i++) {
            long start = System.nanoTime();
            FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, limit, windowSeconds);
            long elapsed = System.nanoTime() - start;
            totalNanos += elapsed;
            minLatency = Math.min(minLatency, elapsed);
            maxLatency = Math.max(maxLatency, elapsed);
            if (result.isAllowed()) allowed++;
            else rejected++;
        }

        long memoryBytes = fixedWindowAlgorithm.getMemoryUsage(key);
        fixedWindowAlgorithm.resetWindow(key);

        return AlgorithmBenchmarkResult.builder()
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .totalRequests(requestCount)
                .allowedRequests(allowed)
                .rejectedRequests(rejected)
                .totalTimeNanos(totalNanos)
                .avgLatencyNanos(totalNanos / requestCount)
                .minLatencyNanos(minLatency)
                .maxLatencyNanos(maxLatency)
                .memoryBytesPerKey(memoryBytes)
                .complexity("O(1) - Single INCR key")
                .burstBehavior("2x burst possible at window boundary")
                .build();
    }

    private AlgorithmBenchmarkResult benchmarkAdaptive(String identifier, int limit, int requestCount) {
        String key = "bench:ad:" + identifier + ":" + UUID.randomUUID();
        int windowSeconds = 60;

        long totalNanos = 0;
        int allowed = 0;
        int rejected = 0;
        long minLatency = Long.MAX_VALUE;
        long maxLatency = 0;

        for (int i = 0; i < requestCount; i++) {
            long start = System.nanoTime();
            AdaptiveResult result = adaptiveRateLimiter.tryConsume(key, limit, windowSeconds);
            long elapsed = System.nanoTime() - start;
            totalNanos += elapsed;
            minLatency = Math.min(minLatency, elapsed);
            maxLatency = Math.max(maxLatency, elapsed);
            if (result.isAllowed()) allowed++;
            else rejected++;
        }

        long memoryBytes = fixedWindowAlgorithm.getMemoryUsage(key);

        return AlgorithmBenchmarkResult.builder()
                .algorithm(RateLimitAlgorithm.ADAPTIVE)
                .totalRequests(requestCount)
                .allowedRequests(allowed)
                .rejectedRequests(rejected)
                .totalTimeNanos(totalNanos)
                .avgLatencyNanos(totalNanos / requestCount)
                .minLatencyNanos(minLatency)
                .maxLatencyNanos(maxLatency)
                .memoryBytesPerKey(memoryBytes)
                .complexity("O(1) - Fixed Window + health check overhead")
                .burstBehavior("Auto-tightens under load, self-healing")
                .build();
    }

    private String buildRecommendation(
            AlgorithmBenchmarkResult tb,
            AlgorithmBenchmarkResult sw,
            AlgorithmBenchmarkResult fw,
            AlgorithmBenchmarkResult ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RECOMMENDATION ===\n");
        sb.append("• Token Bucket: Best for high-throughput APIs (burst-friendly, lowest latency).\n");
        sb.append("• Sliding Window: Best for strict APIs (login, OTP, payments — no burst, most precise).\n");
        sb.append("• Fixed Window: Best for simple internal APIs (lowest memory, fastest O(1), but 2x boundary burst).\n");
        sb.append("• Adaptive: Best for auto-scaling systems (self-healing, adjusts to server load).\n\n");

        // Find fastest
        long fastestAvg = Math.min(Math.min(tb.getAvgLatencyNanos(), sw.getAvgLatencyNanos()),
                Math.min(fw.getAvgLatencyNanos(), ad.getAvgLatencyNanos()));
        sb.append("Fastest avg latency: ");
        if (fastestAvg == tb.getAvgLatencyNanos()) sb.append("TOKEN_BUCKET");
        else if (fastestAvg == fw.getAvgLatencyNanos()) sb.append("FIXED_WINDOW");
        else if (fastestAvg == sw.getAvgLatencyNanos()) sb.append("SLIDING_WINDOW");
        else sb.append("ADAPTIVE");
        sb.append(" (").append(fastestAvg / 1000).append("µs)\n");

        // Find lowest memory
        long lowestMem = Math.min(Math.min(tb.getMemoryBytesPerKey(), sw.getMemoryBytesPerKey()),
                Math.min(fw.getMemoryBytesPerKey(), ad.getMemoryBytesPerKey()));
        sb.append("Lowest memory: ");
        if (lowestMem == fw.getMemoryBytesPerKey()) sb.append("FIXED_WINDOW");
        else if (lowestMem == tb.getMemoryBytesPerKey()) sb.append("TOKEN_BUCKET");
        else if (lowestMem == ad.getMemoryBytesPerKey()) sb.append("ADAPTIVE");
        else sb.append("SLIDING_WINDOW");
        sb.append(" (").append(lowestMem).append(" bytes)\n");

        return sb.toString();
    }

    private void saveBenchmarkToDb(AlgorithmBenchmarkResult result) {
        if (benchmarkRepository != null) {
            try {
                double avgLatencyMs = result.getAvgLatencyNanos() / 1_000_000.0;
                double requestsPerSecond = 0.0;
                if (result.getTotalTimeNanos() > 0) {
                    requestsPerSecond = result.getTotalRequests() / (result.getTotalTimeNanos() / 1_000_000_000.0);
                }
                double errorRate = 0.0;
                if (result.getTotalRequests() > 0) {
                    errorRate = (double) result.getRejectedRequests() / result.getTotalRequests();
                }

                AlgorithmBenchmark entity = AlgorithmBenchmark.builder()
                        .algorithm(result.getAlgorithm().name())
                        .requestsPerSecond(requestsPerSecond)
                        .latencyMs(avgLatencyMs)
                        .throughput((long) result.getTotalRequests())
                        .errorRate(errorRate)
                        .testedAt(Instant.now())
                        .build();

                benchmarkRepository.save(entity);
            } catch (Exception e) {
                log.warn("Failed to persist benchmark result for {}: {}", result.getAlgorithm(), e.getMessage());
            }
        }
    }

    private RateLimitRules.EndpointRule resolveRule(String endpoint) {
        if (rules.getEndpoints() != null) {
            RateLimitRules.EndpointRule rule = rules.getEndpoints().get(endpoint);
            if (rule != null) return rule;
            return rules.getEndpoints().get("api-global");
        }
        return null;
    }

    // --- DTOs ---

    @Data
    @Builder
    public static class FullBenchmarkResult {
        private String identifier;
        private String endpoint;
        private int requestCount;
        private int limit;
        private AlgorithmBenchmarkResult tokenBucket;
        private AlgorithmBenchmarkResult slidingWindow;
        private AlgorithmBenchmarkResult fixedWindow;
        private AlgorithmBenchmarkResult adaptive;
        private String recommendation;
        private Instant timestamp;
    }

    @Data
    @Builder
    public static class AlgorithmBenchmarkResult {
        private RateLimitAlgorithm algorithm;
        private int totalRequests;
        private int allowedRequests;
        private int rejectedRequests;
        private long totalTimeNanos;
        private long avgLatencyNanos;
        private long minLatencyNanos;
        private long maxLatencyNanos;
        private long memoryBytesPerKey;
        private String complexity;
        private String burstBehavior;
    }
}
