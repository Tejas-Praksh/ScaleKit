package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Result of benchmarking all cache strategies.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyBenchmarkResult {
    private Map<String, StrategyMetrics> results; // key = strategy name
    private String fastestWriteStrategy;
    private String fastestReadStrategy;
    private String mostConsistentStrategy;
    private String recommendation; // which to use based on workload
    private Instant benchmarkedAt;
}
