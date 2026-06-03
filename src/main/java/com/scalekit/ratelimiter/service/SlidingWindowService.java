package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.scalekit.ratelimiter.algorithm.SlidingWindowResult;
import com.scalekit.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketResult;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.AlgorithmComparisonResult;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service orchestrating Sliding Window rate limit checks and metric logging.
 */
@Service
@Slf4j
public class SlidingWindowService {

    private final SlidingWindowAlgorithm slidingWindowAlgorithm;
    private final RateLimitRules rules;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;
    
    @Autowired(required = false)
    private TokenBucketService tokenBucketService;

    @Autowired(required = false)
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired
    public SlidingWindowService(
            SlidingWindowAlgorithm slidingWindowAlgorithm,
            RateLimitRules rules,
            MeterRegistry meterRegistry,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.slidingWindowAlgorithm = slidingWindowAlgorithm;
        this.rules = rules;
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Convenience constructor for tests.
     */
    public SlidingWindowService(
            SlidingWindowAlgorithm slidingWindowAlgorithm,
            RateLimitRules rules,
            MeterRegistry meterRegistry) {
        this(slidingWindowAlgorithm, rules, meterRegistry, null);
    }

    public RateLimitResponse isAllowed(String identifier, String endpoint) {
        long startNanos = System.nanoTime();

        // 1. Get rule for endpoint
        RateLimitRules.EndpointRule rule = rules.getEndpoints() != null ? rules.getEndpoints().get(endpoint) : null;
        if (rule == null) {
            rule = rules.getEndpoints() != null ? rules.getEndpoints().get("api-global") : null;
        }

        // If still null or disabled, allow (fail open)
        if (rule == null || !rule.isEnabled()) {
            return RateLimitResponse.builder()
                    .allowed(true)
                    .remainingRequests(100)
                    .limitPerMinute(100)
                    .burstSize(0)
                    .retryAfterMs(0L)
                    .identifier(identifier)
                    .endpoint(endpoint)
                    .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                    .checkTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }

        // 2. Build Redis key: "sw:{endpoint}:{identifier}"
        String key = "sw:" + endpoint + ":" + identifier;

        // 3. Map capacity and window (default window is 1 minute = 60000ms)
        int limit = rule.getRequestsPerMinute();
        long windowSizeMs = 60000L; // sliding window default

        // 4. Call SlidingWindowAlgorithm
        SlidingWindowResult result = slidingWindowAlgorithm.tryConsume(key, limit, windowSizeMs);
        long elapsedNanos = System.nanoTime() - startNanos;

        // 5. Record metrics
        try {
            meterRegistry.counter("scalekit.ratelimit.requests.total",
                    "algorithm", "SLIDING_WINDOW",
                    "endpoint", endpoint,
                    "identifier", identifier,
                    "allowed", String.valueOf(result.isAllowed())
            ).increment();

            if (result.isAllowed()) {
                meterRegistry.counter("sliding.window.allowed", "endpoint", endpoint).increment();
            } else {
                meterRegistry.counter("sliding.window.rejected", "endpoint", endpoint).increment();
            }

            meterRegistry.timer("sliding.window.check.duration", "endpoint", endpoint)
                    .record(java.time.Duration.ofNanos(elapsedNanos));

        } catch (Exception e) {
            log.warn("Failed to increment sliding window metrics: {}", e.getMessage());
        }

        // 6. Return RateLimitResponse
        return RateLimitResponse.builder()
                .allowed(result.isAllowed())
                .remainingRequests(result.getRemainingRequests())
                .limitPerMinute(rule.getRequestsPerMinute())
                .burstSize(0) // Sliding Window has no burst
                .retryAfterMs(result.getResetAfterMs())
                .identifier(identifier)
                .endpoint(endpoint)
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .checkTimeNanos(elapsedNanos)
                .build();
    }

    public int getRemainingRequests(String identifier, String endpoint) {
        String key = "sw:" + endpoint + ":" + identifier;
        return (int) slidingWindowAlgorithm.getRequestCount(key, 60000L);
    }

    public void resetLimit(String identifier, String endpoint) {
        String key = "sw:" + endpoint + ":" + identifier;
        slidingWindowAlgorithm.resetWindow(key);
    }

    public Map<String, Long> getAllBucketStats() {
        Map<String, Long> stats = new HashMap<>();
        if (redisTemplate == null) {
            return stats;
        }
        try {
            Set<String> keys = redisTemplate.keys("sw:*");
            if (keys != null) {
                for (String key : keys) {
                    long count = slidingWindowAlgorithm.getRequestCount(key, 60000L);
                    stats.put(key, count);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve all sliding window stats: {}", e.getMessage());
        }
        return stats;
    }

    public AlgorithmComparisonResult compareWithTokenBucket(String identifier, String endpoint) {
        // Run Sliding Window
        long startSw = System.nanoTime();
        RateLimitResponse swResponse = isAllowed(identifier, endpoint);
        long latencySw = System.nanoTime() - startSw;
        String swKey = "sw:" + endpoint + ":" + identifier;
        long memSw = slidingWindowAlgorithm.getMemoryUsage(swKey);

        // Run Token Bucket
        long latencyTb = 0L;
        long memTb = 0L;
        boolean allowedTb = true;
        int remainingTb = 100;
        if (tokenBucketService != null && tokenBucketAlgorithm != null) {
            long startTb = System.nanoTime();
            RateLimitResponse tbResponse = tokenBucketService.isAllowed(identifier, endpoint);
            latencyTb = System.nanoTime() - startTb;
            String tbKey = "tb:" + endpoint + ":" + identifier;
            memTb = tokenBucketAlgorithm.getMemoryUsage(tbKey);
            allowedTb = tbResponse.isAllowed();
            remainingTb = tbResponse.getRemainingRequests();
        }

        // Recommend
        String recommendation = "TOKEN_BUCKET";
        String explanation = "Token Bucket is recommended for high-volume endpoints (e.g. url-redirect) because it permits traffic bursting and uses significantly less Redis memory (O(1) vs O(N) per user).";
        
        if ("safety-check".equals(endpoint)) {
            recommendation = "SLIDING_WINDOW";
            explanation = "Sliding Window is recommended for strict endpoints like safety-check because it guarantees no burst capacity and applies precise rolling rate limit windows.";
        }

        return AlgorithmComparisonResult.builder()
                .identifier(identifier)
                .endpoint(endpoint)
                .requestsInWindow((int) slidingWindowAlgorithm.getRequestCount(swKey, 60000L))
                .algorithm1(RateLimitAlgorithm.SLIDING_WINDOW)
                .allowed1(swResponse.isAllowed())
                .remaining1(swResponse.getRemainingRequests())
                .latencyNanos1(latencySw)
                .memoryBytes1(memSw)
                .algorithm2(RateLimitAlgorithm.TOKEN_BUCKET)
                .allowed2(allowedTb)
                .remaining2(remainingTb)
                .latencyNanos2(latencyTb)
                .memoryBytes2(memTb)
                .recommendation(recommendation)
                .explanation(explanation)
                .build();
    }
}
