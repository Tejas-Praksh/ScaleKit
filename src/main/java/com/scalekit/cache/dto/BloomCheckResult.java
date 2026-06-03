package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of checking an item against a Bloom Filter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloomCheckResult {
    private String item;
    private boolean mightExist;
    private boolean definitelyNotExist;
    private String explanation;
    private double falsePositiveProbability;
}
