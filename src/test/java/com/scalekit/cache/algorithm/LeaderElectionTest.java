package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.LeaderElectionStats;
import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class LeaderElectionTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    @Qualifier("lockWatchdogExecutor")
    private ScheduledExecutorService scheduler;

    private LeaderElection node1;
    private LeaderElection node2;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        // Clear previous election keys
        redisTemplate.delete("leader:election");

        // Set up custom leader election instances with short TTLs for faster testing
        node1 = new LeaderElection(redisTemplate, scheduler, 1000, 300);
        node2 = new LeaderElection(redisTemplate, scheduler, 1000, 300);
    }

    @Test
    void tryBecomeLeader_available_succeeds() {
        assertTrue(node1.tryBecomeLeader());
        assertTrue(node1.isCurrentLeader());
        assertEquals(node1.getNodeId(), node1.getCurrentLeader());
        node1.resignLeadership();
    }

    @Test
    void tryBecomeLeader_alreadyLeader_fails() {
        assertTrue(node1.tryBecomeLeader());
        
        // Node 2 tries to elect itself but fails since Node 1 is active
        assertFalse(node2.tryBecomeLeader());
        assertFalse(node2.isCurrentLeader());
        assertEquals(node1.getNodeId(), node2.getCurrentLeader());

        node1.resignLeadership();
    }

    @Test
    void heartbeat_renewsLeadership() throws InterruptedException {
        assertTrue(node1.tryBecomeLeader());
        
        // Sleep longer than TTL (1000ms). Heartbeat runs every 300ms, keeping Node 1 leader.
        Thread.sleep(1200);

        assertTrue(node1.isCurrentLeader());
        assertEquals(node1.getNodeId(), node1.getCurrentLeader());

        node1.resignLeadership();
    }

    @Test
    void leaderCrash_newLeaderElected() throws InterruptedException {
        assertTrue(node1.tryBecomeLeader());
        assertEquals(node1.getNodeId(), node1.getCurrentLeader());

        // Simulate crash by stopping node 1's heartbeat without resigning
        node1.stopHeartbeat();

        // Wait for TTL (1000ms) to expire
        long start = System.currentTimeMillis();
        Thread.sleep(1100);

        // Node 2 tries to claim leadership now that Node 1's lease has expired
        assertTrue(node2.tryBecomeLeader());
        assertTrue(node2.isCurrentLeader());
        assertEquals(node2.getNodeId(), node2.getCurrentLeader());

        long failoverTime = System.currentTimeMillis() - start;
        assertTrue(failoverTime < 2000, "Failover must happen quickly after TTL expiry");

        node2.resignLeadership();
    }

    @Test
    void onlyOneLeader_concurrent() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        LeaderElection[] nodes = new LeaderElection[threadCount];
        for (int i = 0; i < threadCount; i++) {
            nodes[i] = new LeaderElection(redisTemplate, scheduler, 1000, 300);
        }

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (nodes[index].tryBecomeLeader()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one node must become leader under high concurrency");

        // Clean up
        for (LeaderElection node : nodes) {
            node.resignLeadership();
        }
    }
}
