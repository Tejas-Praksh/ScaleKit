package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsistentHashMetrics {
    private int nodeCount;
    private Map<String, Long> distribution;
    private double distributionScore;
}
