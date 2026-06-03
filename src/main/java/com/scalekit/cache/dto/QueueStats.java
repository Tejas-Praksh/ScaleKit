package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics and health telemetry of a simple message queue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStats {
    private String queueName;
    private long totalEnqueued;
    private long totalDequeued;
    private long totalFailed;
    private long totalDLQ;
    private long currentSize;
    private long currentDLQSize;
    private double avgProcessingTimeMs;
}
