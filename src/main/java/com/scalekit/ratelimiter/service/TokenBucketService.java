package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketResult;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service orchestrating Token Bucket rate limit checks and metric logging.
 */
@Service
@Slf4j
public class TokenBucketService {

    private final TokenBucketAlgorithm tokenBucketAlgorithm;
    private final RateLimitRules rules;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public TokenBucketService(
            TokenBucketAlgorithm tokenBucketAlgorithm,
            RateLimitRules rules,
            MeterRegistry meterRegistry,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.tokenBucketAlgorithm = tokenBucketAlgorithm;
        this.rules = rules;
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Convenience constructor for tests — defaults {@code redisTemplate} to {@code null}.
     */
    public TokenBucketService(
            TokenBucketAlgorithm tokenBucketAlgorithm,
            RateLimitRules rules,
            MeterRegistry meterRegistry) {
        this(tokenBucketAlgorithm, rules, meterRegistry, null);
    }

    public RateLimitResponse isAllowed(String identifier, String endpoint) {
        long startNanos = System.nanoTime();

        // 1. Get rule for endpoint
        RateLimitRules.EndpointRule rule = rules.getEndpoints() != null ? rules.getEndpoints().get(endpoint) : null;
        if (rule == null) {
            // Fall back to api-global rule if endpoint rule is not configured
            rule = rules.getEndpoints() != null ? rules.getEndpoints().get("api-global") : null;
        }

        // If still null or disabled, allow request (fail open)
        if (rule == null || !rule.isEnabled()) {
            return RateLimitResponse.builder()
                    .allowed(true)
                    .remainingRequests(100)
                    .limitPerMinute(100)
                    .burstSize(20)
                    .retryAfterMs(0L)
                    .identifier(identifier)
                    .endpoint(endpoint)
                    .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                    .checkTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }

        // 2. Build Redis key: "tb:{endpoint}:{identifier}"
        String key = "tb:" + endpoint + ":" + identifier;

        // 3. Map capacity and refill rates
        double capacity = rule.getBurstSize() > 0 ? rule.getBurstSize() : rule.getRequestsPerMinute();
        double refillRate = rule.getRequestsPerMinute() / 60.0; // refills per second

        // 4. Call TokenBucketAlgorithm
        TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, capacity, refillRate);

        // 5. Record metrics
        try {
            meterRegistry.counter("scalekit.ratelimit.requests.total",
                    "endpoint", endpoint,
                    "identifier", identifier,
                    "allowed", String.valueOf(result.isAllowed())
            ).increment();

            if (!result.isAllowed()) {
                meterRegistry.counter("scalekit.ratelimit.requests.rejected",
                        "endpoint", endpoint,
                        "identifier", identifier
                ).increment();
            }
        } catch (Exception e) {
            log.warn("Failed to increment rate limit metrics: {}", e.getMessage());
        }

        // 6. Return RateLimitResponse
        return RateLimitResponse.builder()
                .allowed(result.isAllowed())
                .remainingRequests(result.getRemainingTokens())
                .limitPerMinute(rule.getRequestsPerMinute())
                .burstSize(rule.getBurstSize())
                .retryAfterMs(result.getRetryAfterMs())
                .identifier(identifier)
                .endpoint(endpoint)
                .algorithm(rule.getAlgorithm() != null ? rule.getAlgorithm() : RateLimitAlgorithm.TOKEN_BUCKET)
                .checkTimeNanos(System.nanoTime() - startNanos)
                .build();
    }

    public RateLimitResponse isAllowedByIp(String ip, String endpoint) {
        return isAllowed(ip, endpoint);
    }

    public RateLimitResponse isAllowedByUser(String userId, String endpoint) {
        return isAllowed(userId, endpoint);
    }

    public int getRemainingRequests(String identifier, String endpoint) {
        String key = "tb:" + endpoint + ":" + identifier;
        return (int) Math.floor(tokenBucketAlgorithm.getRemainingTokens(key));
    }

    public void resetLimit(String identifier, String endpoint) {
        String key = "tb:" + endpoint + ":" + identifier;
        tokenBucketAlgorithm.resetBucket(key);
    }

    public Map<String, TokenBucketAlgorithm.BucketState> getAllBucketStats() {
        Map<String, TokenBucketAlgorithm.BucketState> stats = new HashMap<>();
        if (redisTemplate == null) {
            return stats;
        }
        try {
            Set<String> keys = redisTemplate.keys("tb:*");
            if (keys != null) {
                for (String key : keys) {
                    tokenBucketAlgorithm.getBucketStats(key).ifPresent(state -> stats.put(key, state));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve all bucket stats from Redis: {}", e.getMessage());
        }
        return stats;
    }
}
