package com.scalekit.cache.controller;

import com.scalekit.cache.algorithm.LeaderElection;
import com.scalekit.cache.dto.LeaderElectionDemo;
import com.scalekit.cache.dto.LeaderElectionStats;
import com.scalekit.cache.service.LeaderElectionService;
import com.scalekit.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Controller exposing endpoints for checking leader status and triggering failover demos.
 */
@RestController
@RequestMapping("/api/v1/leader")
@Tag(name = "Leader Election", description = "Endpoints for managing and monitoring leader election nodes")
@Slf4j
public class LeaderElectionController {

    private final LeaderElection leaderElection;
    private final RedisTemplate<String, String> redis;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public LeaderElectionController(LeaderElection leaderElection,
                                     RedisTemplate<String, String> redis,
                                     @Qualifier("lockWatchdogExecutor") ScheduledExecutorService scheduler) {
        this.leaderElection = leaderElection;
        this.redis = redis;
        this.scheduler = scheduler;
    }

    @GetMapping("/current")
    @Operation(summary = "Get the nodeId of the current active cluster leader")
    public ApiResponse<String> getCurrentLeader() {
        return ApiResponse.success(leaderElection.getCurrentLeader(), "Current leader fetched");
    }

    @GetMapping("/is-leader")
    @Operation(summary = "Check if this node is currently the leader")
    public ApiResponse<Boolean> isLeader() {
        return ApiResponse.success(leaderElection.isCurrentLeader(), "Leader check completed");
    }

    @PostMapping("/elect")
    @Operation(summary = "Manually trigger this node to try to become leader")
    public ApiResponse<Boolean> elect() {
        boolean success = leaderElection.tryBecomeLeader();
        return ApiResponse.success(success, success ? "Node successfully claimed leadership" : "Node failed to claim leadership");
    }

    @PostMapping("/resign")
    @Operation(summary = "Manually resign leadership from this node")
    public ApiResponse<Boolean> resign() {
        leaderElection.resignLeadership();
        return ApiResponse.success(true, "Resigned leadership successfully");
    }

    @GetMapping("/stats")
    @Operation(summary = "Fetch leader election stats for this node")
    public ApiResponse<LeaderElectionStats> stats() {
        return ApiResponse.success(leaderElection.getStats(), "Leader stats fetched");
    }

    @PostMapping("/demo")
    @Operation(summary = "Demonstrate active leader crash and automatic secondary node failover")
    public ApiResponse<LeaderElectionDemo> demo() {
        long startTime = System.currentTimeMillis();
        
        // Reset state
        leaderElection.resignLeadership();
        
        // 1. Node A (our current node) becomes leader
        leaderElection.tryBecomeLeader();
        String initialLeader = leaderElection.getNodeId();

        // 2. Node B (simulated node) is constructed
        LeaderElection simulatedNodeB = new LeaderElection(redis, scheduler);
        
        // 3. Node A crashes (resigns)
        leaderElection.resignLeadership();

        // 4. Node B immediately elects itself
        boolean successB = simulatedNodeB.tryBecomeLeader();
        String newLeader = simulatedNodeB.getNodeId();

        long duration = System.currentTimeMillis() - startTime;

        LeaderElectionDemo demo = LeaderElectionDemo.builder()
                .initialLeader(initialLeader)
                .newLeader(successB ? newLeader : "None")
                .failoverTimeMs(duration)
                .explanation("Demonstrated Node A acquiring leadership, resigning (simulating crash), and Node B immediately claiming leadership of the cluster.")
                .build();

        return ApiResponse.success(demo, "Failover demonstration completed");
    }
}
