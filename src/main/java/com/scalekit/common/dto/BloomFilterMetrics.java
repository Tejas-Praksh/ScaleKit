package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloomFilterMetrics {
    private int totalFilters;
    private long totalInserted;
    private double avgFillRatio;
    private double estimatedFPR;
}
