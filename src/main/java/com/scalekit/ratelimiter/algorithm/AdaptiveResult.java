package com.scalekit.ratelimiter.algorithm;

import com.scalekit.ratelimiter.dto.SystemHealthDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result wrapper for Adaptive Rate Limiting decisions under variable health conditions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveResult {
    private boolean allowed;
    private int remainingRequests;
    private int baseLimit;
    private int adaptiveLimit;
    private double healthFactor;
    private String healthStatus;
    private SystemHealthDto systemHealth;
    private long executionTimeNanos;
    private long currentCount;
    private long windowTtlSeconds;
}

