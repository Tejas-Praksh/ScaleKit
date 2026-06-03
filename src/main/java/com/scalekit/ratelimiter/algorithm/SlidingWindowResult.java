package com.scalekit.ratelimiter.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a Sliding Window rate limit check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlidingWindowResult {
    private boolean allowed;
    private int remainingRequests;
    private int limit;
    private long resetAfterMs;
    private long windowSizeMs;
    private long requestCountInWindow;
    private String key;
    private long executionTimeNanos;

    @Builder.Default
    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.SLIDING_WINDOW;
}
