package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetrics {
    private double cpuUsage;
    private double heapUsage;
    private long uptimeMs;
    private int activeThreads;
    private double requestsPerSecond;
    private double errorRate;
    private double avgResponseTimeMs;
}
