package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics for URL duplicate detection via Bloom Filter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlDuplicateStats {
    private long urlsAdded;
    private long duplicatesBlocked;
    private double estimatedFPR;
    private BloomFilterStats filterStats;
}
