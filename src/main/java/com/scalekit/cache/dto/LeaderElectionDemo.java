package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a leader election and failover demonstration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderElectionDemo {
    private String initialLeader;
    private String newLeader;
    private long failoverTimeMs;
    private String explanation;
}
