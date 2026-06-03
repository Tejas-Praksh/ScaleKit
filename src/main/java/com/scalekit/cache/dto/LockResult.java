package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Result of a distributed lock acquisition attempt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockResult {
    private boolean acquired;
    private String lockKey;
    private String lockValue;
    private long fencingToken;
    private long validityTimeMs;
    private long acquisitionTimeMs;
    private int nodesAcquired;
    private int nodesTotal;
    private String failureReason;
    private Instant acquiredAt;

    public static LockResult success(String lockKey, String lockValue, long fencingToken, long validityTimeMs, long acquisitionTimeMs, int nodesAcquired, int nodesTotal) {
        return LockResult.builder()
                .acquired(true)
                .lockKey(lockKey)
                .lockValue(lockValue)
                .fencingToken(fencingToken)
                .validityTimeMs(validityTimeMs)
                .acquisitionTimeMs(acquisitionTimeMs)
                .nodesAcquired(nodesAcquired)
                .nodesTotal(nodesTotal)
                .acquiredAt(Instant.now())
                .build();
    }

    public static LockResult failed(String lockKey, String reason) {
        return LockResult.builder()
                .acquired(false)
                .lockKey(lockKey)
                .failureReason(reason)
                .acquiredAt(Instant.now())
                .build();
    }
}
