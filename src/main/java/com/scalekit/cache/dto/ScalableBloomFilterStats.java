package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aggregate statistics for a Scalable Bloom Filter (multiple layers).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScalableBloomFilterStats {
    private List<BloomFilterStats> filterStats;
    private int totalFilters;
    private int totalInserted;
    private double overallFPR;
    private long totalBitsUsed;
}
