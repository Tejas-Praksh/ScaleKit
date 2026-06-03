package com.scalekit.ratelimiter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * System health indicators parsed for Adaptive Rate Limiting decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthDto {
    private double heapUsagePercent;
    private double cpuUsagePercent;
    private long freeMemoryMb;
    private long totalMemoryMb;
    private long usedMemoryMb;
    private int availableProcessors;
    private double healthFactor;
    private String status;
    private Instant measuredAt;
    private String recommendation;
}

