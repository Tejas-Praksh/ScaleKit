package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Representation of an active lock's details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockInfo {
    private String lockKey;
    private String lockValue;
    private long fencingToken;
    private Instant acquiredAt;
    private long ttlMs;
    private String owner;
    private boolean watchdogActive;
}
