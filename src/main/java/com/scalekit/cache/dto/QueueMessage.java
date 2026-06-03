package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Message wrapper for the simple Redis-backed queue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueMessage {
    private String messageId;
    private String payload;
    private Instant enqueuedAt;
    private int retryCount;
    private Map<String, String> headers;
    private Instant processedAt;
}
