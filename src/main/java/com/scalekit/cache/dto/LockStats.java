package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Statistics and health metrics of the distributed lock manager.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockStats {
    private long totalAcquired;
    private long totalReleased;
    private long totalFailed;
    private long totalTimeouts;
    private long currentActiveLocks;
    private double avgHoldTimeMs;
    private double avgAcquisitionTimeMs;
    private List<String> potentialDeadlocks;
    private Map<String, Long> locksByResource;
}
