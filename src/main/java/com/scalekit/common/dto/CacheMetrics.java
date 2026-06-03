package com.scalekit.common.dto;

import com.scalekit.cache.dto.CacheStats;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheMetrics {
    private Map<String, CacheStats> allCacheStats;
    private double overallHitRate;
    private long totalEvictions;
}
