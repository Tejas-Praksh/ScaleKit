package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSnapshot {
    private Instant timestamp;
    private SystemMetrics system;
    private UrlShortenerMetrics urls;
    private RateLimiterMetrics rateLimiter;
    private CacheMetrics cache;
    private ConsistentHashMetrics hashRing;
    private BloomFilterMetrics bloomFilter;
    private QueueMetrics queue;
    private List<AlertDto> activeAlerts;
}
