package com.scalekit.common.dto;

import com.scalekit.cache.dto.QueueStats;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueMetrics {
    private Map<String, QueueStats> queues;
    private long totalPending;
    private long totalDLQ;
}
