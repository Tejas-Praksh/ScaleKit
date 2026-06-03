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
public class RateLimiterMetrics {
    private long requestsAllowed;
    private long requestsRejected;
    private double rejectionRate;
    private Map<String, Long> byAlgorithm;
    private Map<String, Long> byEndpoint;
}
