package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.LeaderElectionStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom Leader Election algorithm using Redis SETNX and heartbeats.
 * Ensures only one node behaves as the active leader across multiple instances.
 */
@Component
@Slf4j
public class LeaderElection {

    private static final String ELECT_SCRIPT = """
            local key = KEYS[1]
            local nodeId = ARGV[1]
            local ttl = tonumber(ARGV[2])

            local current = redis.call('GET', key)
            if current == false then
              redis.call('SET', key, nodeId, 'PX', ttl)
              return 1
            elseif current == nodeId then
              redis.call('PEXPIRE', key, ttl)
              return 1
            else
              return 0
            end
            """;

    private final RedisScript<Long> electScript = RedisScript.of(ELECT_SCRIPT, Long.class);

    private final RedisTemplate<String, String> redis;
    private final ScheduledExecutorService scheduler;

    private final String nodeId = UUID.randomUUID().toString();
    private final String LEADER_KEY = "leader:election";
    private long leaderTtlMs = 10000; // 10 seconds
    private long heartbeatMs = 3000;   // 3 seconds

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicInteger electionCount = new AtomicInteger();
    private final AtomicInteger leadershipLost = new AtomicInteger();
    private final long startTime = System.currentTimeMillis();

    private ScheduledFuture<?> heartbeatTask;

    @Autowired
    public LeaderElection(RedisTemplate<String, String> redis,
                          @Qualifier("lockWatchdogExecutor") ScheduledExecutorService scheduler) {
        this.redis = redis;
        this.scheduler = scheduler;
    }

    public LeaderElection(RedisTemplate<String, String> redis,
                          ScheduledExecutorService scheduler,
                          long leaderTtlMs,
                          long heartbeatMs) {
        this.redis = redis;
        this.scheduler = scheduler;
        this.leaderTtlMs = leaderTtlMs;
        this.heartbeatMs = heartbeatMs;
    }

    /**
     * Attempts to acquire leadership key in Redis.
     */
    public synchronized boolean tryBecomeLeader() {
        if (isLeader.get()) {
            return true;
        }
        try {
            Long result = redis.execute(
                    electScript,
                    Collections.singletonList(LEADER_KEY),
                    nodeId,
                    String.valueOf(leaderTtlMs)
            );
            boolean success = result != null && result == 1L;
            if (success) {
                isLeader.set(true);
                electionCount.incrementAndGet();
                startHeartbeat();
                log.info("Node {} became leader", nodeId);
                return true;
            }
        } catch (Exception e) {
            log.warn("Leader election attempt failed for node '{}': {}", nodeId, e.getMessage());
        }
        return false;
    }

    /**
     * Starts heartbeat scheduler renewing TTL periodically.
     */
    public synchronized void startHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(this::renewLeadership, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels heartbeat scheduler.
     */
    public synchronized void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * Renews leadership key lease in Redis.
     */
    public synchronized void renewLeadership() {
        if (!isLeader.get()) {
            stopHeartbeat();
            return;
        }
        try {
            Long result = redis.execute(
                    electScript,
                    Collections.singletonList(LEADER_KEY),
                    nodeId,
                    String.valueOf(leaderTtlMs)
            );
            boolean success = result != null && result == 1L;
            if (!success) {
                handleLostLeadership();
            }
        } catch (Exception e) {
            log.warn("Failed to renew leadership heartbeat on Redis node: {}", e.getMessage());
            handleLostLeadership();
        }
    }

    private void handleLostLeadership() {
        isLeader.set(false);
        stopHeartbeat();
        leadershipLost.incrementAndGet();
        log.warn("Lost leadership!");
        onLeadershipLost();
    }

    /**
     * Resigns leadership, deleting key immediately.
     */
    public synchronized void resignLeadership() {
        if (isLeader.get()) {
            try {
                String current = redis.opsForValue().get(LEADER_KEY);
                if (nodeId.equals(current)) {
                    redis.delete(LEADER_KEY);
                }
            } catch (Exception e) {
                log.warn("Failed to delete leader key during resignation: {}", e.getMessage());
            }
            isLeader.set(false);
            stopHeartbeat();
            log.info("Resigned leadership");
        }
    }

    public String getCurrentLeader() {
        try {
            return redis.opsForValue().get(LEADER_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isCurrentLeader() {
        return isLeader.get();
    }

    public long getLeaderTtl() {
        try {
            Long expire = redis.getExpire(LEADER_KEY, TimeUnit.MILLISECONDS);
            return expire != null ? expire : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public LeaderElectionStats getStats() {
        return LeaderElectionStats.builder()
                .nodeId(nodeId)
                .isLeader(isLeader.get())
                .electionCount(electionCount.get())
                .leadershipLost(leadershipLost.get())
                .currentLeader(getCurrentLeader())
                .leaderTtlMs(getLeaderTtl())
                .uptimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    public void onLeadershipLost() {
        log.warn("Node {} custom action on leadership loss triggered", nodeId);
    }

    public String getNodeId() {
        return nodeId;
    }
}
