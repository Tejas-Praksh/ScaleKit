package com.scalekit.cache.service;

import com.scalekit.cache.algorithm.LeaderElection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Service providing high-level leader checking, scheduling triggers, and election control.
 */
@Service
@Slf4j
public class LeaderElectionService {

    private final LeaderElection leaderElection;

    @Autowired
    public LeaderElectionService(LeaderElection leaderElection) {
        this.leaderElection = leaderElection;
    }

    @PostConstruct
    public void startElection() {
        log.info("Starting Leader Election Service. Node ID: {}", leaderElection.getNodeId());
    }

    /**
     * Periodically triggers election attempts if the current node is not already the leader.
     */
    @Scheduled(fixedDelay = 5000)
    public void tryElection() {
        if (!leaderElection.isCurrentLeader()) {
            leaderElection.tryBecomeLeader();
        }
    }

    /**
     * Executes a runnable task only if the current node is the active leader.
     */
    public void executeIfLeader(Runnable task) {
        if (leaderElection.isCurrentLeader()) {
            task.run();
        } else {
            log.debug("Not leader, skipping task execution");
        }
    }

    /**
     * Executes a supplier only if the current node is the active leader, returning the result wrapped in an Optional.
     */
    public <T> Optional<T> executeIfLeaderWithResult(Supplier<T> task) {
        if (leaderElection.isCurrentLeader()) {
            return Optional.ofNullable(task.get());
        } else {
            return Optional.empty();
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("LeaderElectionService shutting down for node: {}", leaderElection.getNodeId());
        leaderElection.resignLeadership();
    }
}
