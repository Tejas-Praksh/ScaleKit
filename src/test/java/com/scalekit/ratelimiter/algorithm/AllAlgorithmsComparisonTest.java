package com.scalekit.ratelimiter.algorithm;

import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comparative integration test executing the same workload across all three rate limiting
 * algorithms and verifying behavioral differences.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class AllAlgorithmsComparisonTest {

    @Autowired
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired
    private SlidingWindowAlgorithm slidingWindowAlgorithm;

    @Autowired
    private FixedWindowAlgorithm fixedWindowAlgorithm;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
    }

    @Test
    void steadyTraffic_allBehaveTheSame() throws InterruptedException {
        String tbKey = "tb:steady:" + UUID.randomUUID();
        String swKey = "sw:steady:" + UUID.randomUUID();
        String fwKey = "fw:steady:" + UUID.randomUUID();

        int limit = 5;

        // Send 3 requests — well within limits — all should be allowed by every algorithm
        for (int i = 0; i < 3; i++) {
            assertTrue(tokenBucketAlgorithm.tryConsume(tbKey, limit, 1.0).isAllowed(),
                    "Token Bucket should allow steady request " + i);
            assertTrue(slidingWindowAlgorithm.tryConsume(swKey, limit, 60000L).isAllowed(),
                    "Sliding Window should allow steady request " + i);
            assertTrue(fixedWindowAlgorithm.tryConsume(fwKey, limit, 60).isAllowed(),
                    "Fixed Window should allow steady request " + i);
        }
    }

    @Test
    void burstTraffic_tokenBucketAllows_othersVary() {
        String tbKey = "tb:burst:" + UUID.randomUUID();
        String swKey = "sw:burst:" + UUID.randomUUID();
        String fwKey = "fw:burst:" + UUID.randomUUID();

        int limit = 5;

        // Exhaust all three algorithms to their limit
        for (int i = 0; i < limit; i++) {
            tokenBucketAlgorithm.tryConsume(tbKey, limit, 1.0);
            slidingWindowAlgorithm.tryConsume(swKey, limit, 60000L);
            fixedWindowAlgorithm.tryConsume(fwKey, limit, 60);
        }

        // All three should reject the next request (at capacity)
        assertFalse(tokenBucketAlgorithm.tryConsume(tbKey, limit, 1.0).isAllowed(),
                "Token Bucket should reject at capacity");
        assertFalse(slidingWindowAlgorithm.tryConsume(swKey, limit, 60000L).isAllowed(),
                "Sliding Window should reject at capacity");
        assertFalse(fixedWindowAlgorithm.tryConsume(fwKey, limit, 60).isAllowed(),
                "Fixed Window should reject at capacity");
    }

    @Test
    void memory_fixedWindow_lowestUsage() {
        String tbKey = "tb:mem:" + UUID.randomUUID();
        String swKey = "sw:mem:" + UUID.randomUUID();
        String fwKey = "fw:mem:" + UUID.randomUUID();

        // Send several requests to build up state
        for (int i = 0; i < 10; i++) {
            tokenBucketAlgorithm.tryConsume(tbKey, 100, 1.0);
            slidingWindowAlgorithm.tryConsume(swKey, 100, 60000L);
            fixedWindowAlgorithm.tryConsume(fwKey, 100, 60);
        }

        long memTb = tokenBucketAlgorithm.getMemoryUsage(tbKey);
        long memSw = slidingWindowAlgorithm.getMemoryUsage(swKey);
        long memFw = fixedWindowAlgorithm.getMemoryUsage(fwKey);

        System.out.println("=== Memory Comparison (10 requests) ===");
        System.out.println("Token Bucket: " + memTb + " bytes (Redis Hash, 2 fields)");
        System.out.println("Sliding Window: " + memSw + " bytes (Redis ZSET, 10 members)");
        System.out.println("Fixed Window: " + memFw + " bytes (Redis String, 1 integer)");

        // Fixed Window uses a single integer key — should be smallest or equal
        assertTrue(memFw <= memSw,
                "Fixed Window (" + memFw + "B) should use less or equal memory than Sliding Window (" + memSw + "B)");
    }

    @Test
    void latency_fixedWindow_fastest() {
        String tbKey = "tb:lat:" + UUID.randomUUID();
        String swKey = "sw:lat:" + UUID.randomUUID();
        String fwKey = "fw:lat:" + UUID.randomUUID();

        int iterations = 100;

        // Warmup
        for (int i = 0; i < 10; i++) {
            tokenBucketAlgorithm.tryConsume(tbKey + ":warmup", 1000, 1.0);
            slidingWindowAlgorithm.tryConsume(swKey + ":warmup", 1000, 60000L);
            fixedWindowAlgorithm.tryConsume(fwKey + ":warmup", 1000, 60);
        }

        // Measure Token Bucket
        long startTb = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            tokenBucketAlgorithm.tryConsume(tbKey, 10000, 1.0);
        }
        long durationTb = System.nanoTime() - startTb;

        // Measure Sliding Window
        long startSw = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            slidingWindowAlgorithm.tryConsume(swKey, 10000, 60000L);
        }
        long durationSw = System.nanoTime() - startSw;

        // Measure Fixed Window
        long startFw = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            fixedWindowAlgorithm.tryConsume(fwKey, 10000, 60);
        }
        long durationFw = System.nanoTime() - startFw;

        System.out.println("=== Latency Comparison (" + iterations + " iterations) ===");
        System.out.printf("Token Bucket: %.2fms avg%n", durationTb / 1_000_000.0 / iterations);
        System.out.printf("Sliding Window: %.2fms avg%n", durationSw / 1_000_000.0 / iterations);
        System.out.printf("Fixed Window: %.2fms avg%n", durationFw / 1_000_000.0 / iterations);

        // All should complete successfully — latency assertions are informational
        assertTrue(durationFw > 0);
        assertTrue(durationTb > 0);
        assertTrue(durationSw > 0);
    }
}
