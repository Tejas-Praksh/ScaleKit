package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metrics for a single cache strategy during benchmarking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyMetrics {
    private String strategyName;
    private double avgWriteMs;
    private double avgReadMs;
    private double p99WriteMs;
    private double p99ReadMs;
    private long writesPerSecond;
    private long readsPerSecond;
    private boolean dataConsistency; // true if writes are persisted synchronously
}
