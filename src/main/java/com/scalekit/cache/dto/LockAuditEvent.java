package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event log entry for distributed lock operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockAuditEvent {
    private String lockKey;
    private String ownerId;
    private LockEventType type;
    private long fencingToken;
    private long durationMs;
    private Instant timestamp;
}
