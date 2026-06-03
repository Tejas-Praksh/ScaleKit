package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Statistics for LRU cache implementation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStats {
    private int capacity;
    private int currentSize;
    private long hits;
    private long misses;
    private long evictions;
    private long puts;
    private double hitRate;
    private double fillRate;
    private Object mostRecentKey; // generic, could be K
    private Object leastRecentKey; // generic, could be K
    private Instant measuredAt;
}
