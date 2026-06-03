package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics for a Bloom Filter instance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloomFilterStats {
    private int insertedCount;
    private int bitArraySize;
    private int hashFunctionCount;
    private int expectedInsertions;
    private double configuredFPR;
    private double currentFillRatio;
    private double estimatedCurrentFPR;
    private long positiveChecks;
    private long negativeChecks;
    private long estimatedFalsePositives;
    private boolean isNearCapacity;
    private String recommendation;
}
