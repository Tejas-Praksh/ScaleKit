package com.scalekit.ratelimiter.dto;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Comparison details between two rate limiting algorithms under the same request criteria.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmComparisonResult {
    private String identifier;
    private String endpoint;
    private int requestsInWindow;

    // Algorithm 1
    private RateLimitAlgorithm algorithm1;
    private boolean allowed1;
    private int remaining1;
    private long latencyNanos1;
    private long memoryBytes1;

    // Algorithm 2
    private RateLimitAlgorithm algorithm2;
    private boolean allowed2;
    private int remaining2;
    private long latencyNanos2;
    private long memoryBytes2;

    private String recommendation;
    private String explanation;
}
