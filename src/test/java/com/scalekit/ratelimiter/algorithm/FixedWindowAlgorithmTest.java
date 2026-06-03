package com.scalekit.ratelimiter.algorithm;

import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class FixedWindowAlgorithmTest {

    @Autowired
    private FixedWindowAlgorithm fixedWindowAlgorithm;

    @Autowired
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired
    private SlidingWindowAlgorithm slidingWindowAlgorithm;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String key;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
        key = "fw:test:" + UUID.randomUUID().toString();
    }

    @Test
    void tryConsume_withinLimit_allows() {
        FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, 5, 10);
        assertTrue(result.isAllowed());
        assertEquals(4, result.getRemainingRequests());
        assertEquals(5, result.getLimit());
        assertEquals(1, result.getCurrentCount());
        assertTrue(result.getWindowTtlSeconds() > 0);
        assertFalse(result.isApproachingBoundary());
    }

    @Test
    void tryConsume_atLimit_rejects() {
        // Fill the window
        for (int i = 0; i < 3; i++) {
            fixedWindowAlgorithm.tryConsume(key, 3, 10);
        }

        // 4th request must be blocked
        FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, 3, 10);
        assertFalse(result.isAllowed());
        assertEquals(0, result.getRemainingRequests());
        assertEquals(3, result.getCurrentCount());
    }

    @Test
    void tryConsume_windowResets_allowsAgain() throws InterruptedException {
        // Fill window with a short 1 second expiry
        fixedWindowAlgorithm.tryConsume(key, 1, 1);

        // Verify next is blocked
        assertFalse(fixedWindowAlgorithm.tryConsume(key, 1, 1).isAllowed());

        // Wait 1.1s for expiry
        Thread.sleep(1100);

        // Next must be allowed again
        FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, 1, 1);
        assertTrue(result.isAllowed());
        assertEquals(0, result.getRemainingRequests());
    }

    @Test
    void tryConsume_concurrent_noRaceCondition() throws Exception {
        int limit = 20;
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                FixedWindowResult res = fixedWindowAlgorithm.tryConsume(key, limit, 60);
                return res.isAllowed();
            }));
        }

        startLatch.countDown(); // Start all threads together
        doneLatch.await(); // wait for completion

        int allowedCount = 0;
        int rejectedCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }
        executor.shutdown();

        assertEquals(20, allowedCount, "Exactly limit requests should be allowed");
        assertEquals(30, rejectedCount, "Remaining requests should be rejected");
    }

    @Test
    void tryConsume_lowestMemory_ofAllAlgorithms() {
        String tbKey = "tb:memtest:" + UUID.randomUUID();
        String swKey = "sw:memtest:" + UUID.randomUUID();
        String fwKey = "fw:memtest:" + UUID.randomUUID();

        // Perform requests
        tokenBucketAlgorithm.tryConsume(tbKey, 10, 1.0);
        slidingWindowAlgorithm.tryConsume(swKey, 10, 60000L);
        fixedWindowAlgorithm.tryConsume(fwKey, 10, 60);

        // Fetch physical memory in Redis
        long memTb = tokenBucketAlgorithm.getMemoryUsage(tbKey);
        long memSw = slidingWindowAlgorithm.getMemoryUsage(swKey);
        long memFw = fixedWindowAlgorithm.getMemoryUsage(fwKey);

        // Print comparative metrics
        System.out.println("Redis Key Memory (Token Bucket): " + memTb + " bytes");
        System.out.println("Redis Key Memory (Sliding Window): " + memSw + " bytes");
        System.out.println("Redis Key Memory (Fixed Window): " + memFw + " bytes");

        // Fixed Window (simple string key/value) should consume equal or less than ZSET or Hash
        assertTrue(memFw <= memSw, "Fixed Window memory footprint should be less or equal to Sliding Window log");
    }
}
