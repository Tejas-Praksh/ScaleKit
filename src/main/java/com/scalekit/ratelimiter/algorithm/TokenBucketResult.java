package com.scalekit.ratelimiter.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result DTO representing the outcome of a token bucket rate check request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenBucketResult {
    private boolean allowed;
    private int remainingTokens;
    private int totalCapacity;
    private long retryAfterMs;
    private String key;
    private long executionTimeNanos;

    @Builder.Default
    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;
}
