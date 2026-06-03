package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Statistics for a cache strategy implementation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStrategyStats {
    private String strategyName;
    private long reads;
    private long writes;
    private long cacheHits;
    private long cacheMisses;
    private long dbReads;
    private long dbWrites;
    private double cacheHitRate;
    private double avgReadLatencyMs;
    private double avgWriteLatencyMs;
    private long pendingWriteBehindOps; // for write-behind strategy; 0 for others
    private Instant measuredAt;
}
