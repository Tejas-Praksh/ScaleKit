package com.scalekit.ratelimiter.dto;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * REST response payload wrapping rate limiter verification details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitResponse {
    private boolean allowed;
    private int remainingRequests;
    private int limitPerMinute;
    private int burstSize;
    private long retryAfterMs;
    private String identifier;
    private String endpoint;
    private RateLimitAlgorithm algorithm;
    private long checkTimeNanos;
}
