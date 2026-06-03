package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics and health telemetry of a node in leader election.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderElectionStats {
    private String nodeId;
    private boolean isLeader;
    private int electionCount;
    private int leadershipLost;
    private String currentLeader;
    private long leaderTtlMs;
    private long uptimeMs;
}
