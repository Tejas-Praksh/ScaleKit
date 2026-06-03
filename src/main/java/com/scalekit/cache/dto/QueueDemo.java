package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a message queue publishing and consuming demonstration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueDemo {
    private int messagesSent;
    private int messagesReceived;
    private int messagesFailed;
    private int messagesInDLQ;
    private long processingTimeMs;
    private String explanation;
}
