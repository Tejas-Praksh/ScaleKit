package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * Histogram of frequency distribution for LFU cache.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrequencyHistogram {
    /**
     * Map from frequency value to number of keys having that frequency.
     */
    private Map<Integer, Integer> freqToCount;
    /**
     * Maximum frequency among all keys.
     */
    private int maxFrequency;
    /**
     * Minimum frequency among all keys.
     */
    private int minFrequency;
    /**
     * Average frequency across all keys.
     */
    private double avgFrequency;
    /**
     * Total number of keys.
     */
    private int totalKeys;

    /**
     * Bucket representing a specific frequency.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrequencyBucket {
        private int frequency;
        private int keyCount;
        private double percentage;
        private List<String> sampleKeys;
    }
    /**
     * List of buckets for detailed analysis.
     */
    private List<FrequencyBucket> buckets;
}
