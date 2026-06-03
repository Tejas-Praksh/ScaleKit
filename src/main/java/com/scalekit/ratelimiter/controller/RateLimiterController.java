package com.scalekit.ratelimiter.controller;

import com.scalekit.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import com.scalekit.ratelimiter.service.TokenBucketService;
import com.scalekit.ratelimiter.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for rate limiter introspection and management.
 *
 * <p>Provides endpoints to query remaining request budgets, reset specific
 * rate limit buckets, inspect bucket state, and view configured endpoint rules.
 * All paths are excluded from rate limiting by the filter.
 */
@RestController
@RequestMapping("/api/ratelimit")
@RequiredArgsConstructor
@Slf4j
public class RateLimiterController {

    private final TokenBucketService tokenBucketService;
    private final RateLimiterService rateLimiterService;
    private final RateLimitRules rateLimitRules;

    /**
     * Returns the current rate limit status for a given identifier and endpoint.
     *
     * @param identifier client identifier (IP, user ID, API key)
     * @param endpoint   endpoint key as defined in application.yml
     * @return current rate limit status including remaining tokens
     */
    @GetMapping("/status")
    public ResponseEntity<RateLimitResponse> getStatus(
            @RequestParam String identifier,
            @RequestParam String endpoint) {

        RateLimitResponse response = rateLimiterService.isAllowed(identifier, endpoint);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns remaining request count for an identifier + endpoint pair.
     */
    @GetMapping("/remaining")
    public ResponseEntity<Map<String, Object>> getRemaining(
            @RequestParam String identifier,
            @RequestParam String endpoint) {

        int remaining = rateLimiterService.getRemainingRequests(identifier, endpoint);
        return ResponseEntity.ok(Map.of(
                "identifier", identifier,
                "endpoint", endpoint,
                "remaining", remaining
        ));
    }

    /**
     * Resets the rate limit bucket for a specific identifier and endpoint.
     * Useful for admin operations or testing.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetLimit(
            @RequestParam String identifier,
            @RequestParam String endpoint) {

        rateLimiterService.resetLimit(identifier, endpoint);
        log.info("Rate limit bucket reset for identifier={} endpoint={}", identifier, endpoint);
        return ResponseEntity.ok(Map.of(
                "status", "reset",
                "identifier", identifier,
                "endpoint", endpoint
        ));
    }

    /**
     * Returns a snapshot of all active token buckets in Redis.
     * Use sparingly in production — this scans Redis keys.
     */
    @GetMapping("/buckets")
    public ResponseEntity<Map<String, TokenBucketAlgorithm.BucketState>> getAllBuckets() {
        Map<String, TokenBucketAlgorithm.BucketState> stats = tokenBucketService.getAllBucketStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Returns the currently configured rate limit rules from application.yml.
     */
    @GetMapping("/rules")
    public ResponseEntity<Map<String, RateLimitRules.EndpointRule>> getRules() {
        Map<String, RateLimitRules.EndpointRule> endpoints = rateLimitRules.getEndpoints();
        return ResponseEntity.ok(endpoints != null ? endpoints : Map.of());
    }
}
