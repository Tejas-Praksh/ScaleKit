package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.scalekit.ratelimiter.algorithm.FixedWindowResult;
import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service orchestrating Fixed Window rate limit checks and metric logging.
 *
 * <p>Follows the same pattern as {@link TokenBucketService} and {@link SlidingWindowService}.</p>
 */
@Service
@Slf4j
public class FixedWindowService {

    private final FixedWindowAlgorithm fixedWindowAlgorithm;
    private final RateLimitRules rules;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public FixedWindowService(
            FixedWindowAlgorithm fixedWindowAlgorithm,
            RateLimitRules rules,
            MeterRegistry meterRegistry,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.fixedWindowAlgorithm = fixedWindowAlgorithm;
        this.rules = rules;
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Convenience constructor for tests.
     */
    public FixedWindowService(
            FixedWindowAlgorithm fixedWindowAlgorithm,
            RateLimitRules rules,
            MeterRegistry meterRegistry) {
        this(fixedWindowAlgorithm, rules, meterRegistry, null);
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
                    .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                    .checkTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }

        // 2. Build Redis key: "fw:{endpoint}:{identifier}"
        String key = "fw:" + endpoint + ":" + identifier;

        // 3. Map capacity and window (default 60 seconds)
        int limit = rule.getRequestsPerMinute();
        int windowSeconds = 60;

        // 4. Call FixedWindowAlgorithm
        FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, limit, windowSeconds);
        long elapsedNanos = System.nanoTime() - startNanos;

        // 5. Record metrics
        try {
            meterRegistry.counter("scalekit.ratelimit.requests.total",
                    "algorithm", "FIXED_WINDOW",
                    "endpoint", endpoint,
                    "identifier", identifier,
                    "allowed", String.valueOf(result.isAllowed())
            ).increment();

            if (result.isAllowed()) {
                meterRegistry.counter("fixed.window.allowed", "endpoint", endpoint).increment();
            } else {
                meterRegistry.counter("fixed.window.rejected", "endpoint", endpoint).increment();
            }

            meterRegistry.timer("fixed.window.check.duration", "endpoint", endpoint)
                    .record(Duration.ofNanos(elapsedNanos));

        } catch (Exception e) {
            log.warn("Failed to increment fixed window metrics: {}", e.getMessage());
        }

        // 6. Return RateLimitResponse
        return RateLimitResponse.builder()
                .allowed(result.isAllowed())
                .remainingRequests(result.getRemainingRequests())
                .limitPerMinute(rule.getRequestsPerMinute())
                .burstSize(0) // Fixed Window has no burst concept
                .retryAfterMs(result.getWindowTtlSeconds() * 1000L)
                .identifier(identifier)
                .endpoint(endpoint)
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .checkTimeNanos(elapsedNanos)
                .build();
    }

    public int getRemainingRequests(String identifier, String endpoint) {
        String key = "fw:" + endpoint + ":" + identifier;
        RateLimitRules.EndpointRule rule = rules.getEndpoints() != null ? rules.getEndpoints().get(endpoint) : null;
        if (rule == null) {
            rule = rules.getEndpoints() != null ? rules.getEndpoints().get("api-global") : null;
        }
        int limit = (rule != null) ? rule.getRequestsPerMinute() : 100;
        long count = fixedWindowAlgorithm.getCurrentCount(key);
        return (int) Math.max(0, limit - count);
    }

    public void resetLimit(String identifier, String endpoint) {
        String key = "fw:" + endpoint + ":" + identifier;
        fixedWindowAlgorithm.resetWindow(key);
    }

    public Map<String, Long> getAllWindowStats() {
        Map<String, Long> stats = new HashMap<>();
        if (redisTemplate == null) {
            return stats;
        }
        try {
            Set<String> keys = redisTemplate.keys("fw:*");
            if (keys != null) {
                for (String key : keys) {
                    long count = fixedWindowAlgorithm.getCurrentCount(key);
                    stats.put(key, count);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve all fixed window stats: {}", e.getMessage());
        }
        return stats;
    }
}
