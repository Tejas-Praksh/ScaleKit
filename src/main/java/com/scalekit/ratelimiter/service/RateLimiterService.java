package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.algorithm.AdaptiveRateLimiter;
import com.scalekit.ratelimiter.algorithm.AdaptiveResult;
import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Unified entry point and orchestrator for ScaleKit's multi-algorithm Rate Limiter.
 *
 * <p>Supports all four algorithms: TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW, ADAPTIVE.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final TokenBucketService tokenBucketService;
    private final SlidingWindowService slidingWindowService;
    private final FixedWindowService fixedWindowService;
    private final AdaptiveRateLimiter adaptiveRateLimiter;
    private final RateLimitRules rules;

    /**
     * Rate limit check executing the algorithm configured for the given endpoint.
     */
    public RateLimitResponse isAllowed(String identifier, String endpoint) {
        RateLimitAlgorithm algorithm = resolveAlgorithm(endpoint);
        return isAllowed(identifier, endpoint, algorithm);
    }

    /**
     * Rate limit check using a specific algorithm.
     */
    public RateLimitResponse isAllowed(String identifier, String endpoint, RateLimitAlgorithm algorithm) {
        if (algorithm == null) {
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET;
        }

        return switch (algorithm) {
            case SLIDING_WINDOW -> slidingWindowService.isAllowed(identifier, endpoint);
            case FIXED_WINDOW -> fixedWindowService.isAllowed(identifier, endpoint);
            case ADAPTIVE -> handleAdaptive(identifier, endpoint);
            default -> tokenBucketService.isAllowed(identifier, endpoint);
        };
    }

    /**
     * Obtains the remaining requests for the configured endpoint algorithm.
     */
    public int getRemainingRequests(String identifier, String endpoint) {
        RateLimitAlgorithm algorithm = resolveAlgorithm(endpoint);
        return switch (algorithm) {
            case SLIDING_WINDOW -> slidingWindowService.getRemainingRequests(identifier, endpoint);
            case FIXED_WINDOW -> fixedWindowService.getRemainingRequests(identifier, endpoint);
            default -> tokenBucketService.getRemainingRequests(identifier, endpoint);
        };
    }

    /**
     * Resets rate limit states across all algorithms for safety and simplicity.
     */
    public void resetLimit(String identifier, String endpoint) {
        tokenBucketService.resetLimit(identifier, endpoint);
        slidingWindowService.resetLimit(identifier, endpoint);
        fixedWindowService.resetLimit(identifier, endpoint);
    }

    /**
     * Handles adaptive rate limiting by delegating to the AdaptiveRateLimiter
     * and converting its result to the unified RateLimitResponse.
     */
    private RateLimitResponse handleAdaptive(String identifier, String endpoint) {
        long startNanos = System.nanoTime();

        RateLimitRules.EndpointRule rule = resolveRule(endpoint);
        int baseLimit = (rule != null) ? rule.getRequestsPerMinute() : 100;

        String key = "ad:" + endpoint + ":" + identifier;
        AdaptiveResult result = adaptiveRateLimiter.tryConsume(key, baseLimit, 60);

        return RateLimitResponse.builder()
                .allowed(result.isAllowed())
                .remainingRequests(result.getRemainingRequests())
                .limitPerMinute(result.getAdaptiveLimit())
                .burstSize(0)
                .retryAfterMs(result.getWindowTtlSeconds() * 1000L)
                .identifier(identifier)
                .endpoint(endpoint)
                .algorithm(RateLimitAlgorithm.ADAPTIVE)
                .checkTimeNanos(System.nanoTime() - startNanos)
                .build();
    }

    private RateLimitAlgorithm resolveAlgorithm(String endpoint) {
        if (rules.getEndpoints() != null) {
            RateLimitRules.EndpointRule rule = rules.getEndpoints().get(endpoint);
            if (rule != null && rule.getAlgorithm() != null) {
                return rule.getAlgorithm();
            }
            // Fall back to api-global
            rule = rules.getEndpoints().get("api-global");
            if (rule != null && rule.getAlgorithm() != null) {
                return rule.getAlgorithm();
            }
        }
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    private RateLimitRules.EndpointRule resolveRule(String endpoint) {
        if (rules.getEndpoints() != null) {
            RateLimitRules.EndpointRule rule = rules.getEndpoints().get(endpoint);
            if (rule != null) return rule;
            return rules.getEndpoints().get("api-global");
        }
        return null;
    }
}
