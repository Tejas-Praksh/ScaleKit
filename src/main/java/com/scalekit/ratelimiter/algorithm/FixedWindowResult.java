package com.scalekit.ratelimiter.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a Fixed Window rate limit check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixedWindowResult {
    private boolean allowed;
    private int remainingRequests;
    private int limit;
    private long currentCount;
    private long windowTtlSeconds;
    private boolean approachingBoundary;
    private String key;
    private long executionTimeNanos;

    @Builder.Default
    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.FIXED_WINDOW;
}
