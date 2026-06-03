package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * System health and performance metrics DTO.
 *
 * <p>Used by the analytics system to report per-subsystem metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsDto {

    private String system;
    private long totalRequests;
    private long successRequests;
    private long failedRequests;
    private double successRate;
    private double avgResponseTimeMs;
    private double p99ResponseTimeMs;
    private long cacheHits;
    private long cacheMisses;
    private double cacheHitRate;

    @Builder.Default
    private Instant measuredAt = Instant.now();
}
