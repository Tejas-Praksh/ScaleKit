package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a Bloom Filter false-positive demonstration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloomFilterDemo {
    private int totalInserted;
    private int totalChecked;
    private int truePositives;
    private int trueNegatives;
    private int falsePositives;
    private int falseNegatives;
    private double actualFPR;
    private double expectedFPR;
    private String analysis;
}
