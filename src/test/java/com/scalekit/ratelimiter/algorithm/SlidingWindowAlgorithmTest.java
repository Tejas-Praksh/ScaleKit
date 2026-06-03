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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class SlidingWindowAlgorithmTest {

    @Autowired(required = false)
    private SlidingWindowAlgorithm slidingWindowAlgorithm;

    private String key;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
        assertNotNull(slidingWindowAlgorithm, "SlidingWindowAlgorithm should be autowired when Docker/Redis is active");
        key = "sw:test:" + UUID.randomUUID().toString();
    }

    @Test
    void tryConsume_withinLimit_allows() {
        SlidingWindowResult result = slidingWindowAlgorithm.tryConsume(key, 5, 1000);
        assertTrue(result.isAllowed());
        assertEquals(4, result.getRemainingRequests());
        assertEquals(5, result.getLimit());
        assertEquals(1, result.getRequestCountInWindow());
    }

    @Test
    void tryConsume_atLimit_rejects() {
        for (int i = 0; i < 5; i++) {
            assertTrue(slidingWindowAlgorithm.tryConsume(key, 5, 5000).isAllowed());
        }

        SlidingWindowResult result = slidingWindowAlgorithm.tryConsume(key, 5, 5000);
        assertFalse(result.isAllowed());
        assertEquals(0, result.getRemainingRequests());
        assertTrue(result.getResetAfterMs() > 0);
    }

    @Test
    void tryConsume_windowSlides_allowsAfterExpiry() throws InterruptedException {
        // Fire 3 requests in a 1-second window
        for (int i = 0; i < 3; i++) {
            assertTrue(slidingWindowAlgorithm.tryConsume(key, 3, 1000).isAllowed());
        }

        // 4th request within 1s should be rejected
        assertFalse(slidingWindowAlgorithm.tryConsume(key, 3, 1000).isAllowed());

        // Wait 1.1 seconds for window to slide completely
        Thread.sleep(1100);

        // Should allow requests again
        assertTrue(slidingWindowAlgorithm.tryConsume(key, 3, 1000).isAllowed());
    }

    @Test
    void tryConsume_exactBoundary_correct() throws InterruptedException {
        // Send 1 request
        assertTrue(slidingWindowAlgorithm.tryConsume(key, 2, 500).isAllowed());
        
        // Wait 300ms
        Thread.sleep(300);

        // Send 2nd request
        assertTrue(slidingWindowAlgorithm.tryConsume(key, 2, 500).isAllowed());

        // 3rd is blocked
        assertFalse(slidingWindowAlgorithm.tryConsume(key, 2, 500).isAllowed());

        // Wait another 250ms (total 550ms since first) -> first one has slid out of window, but second remains
        Thread.sleep(250);

        // Can send 1 more request
        assertTrue(slidingWindowAlgorithm.tryConsume(key, 2, 500).isAllowed());
        // A 4th request immediately is rejected
        assertFalse(slidingWindowAlgorithm.tryConsume(key, 2, 500).isAllowed());
    }

    @Test
    void tryConsume_noSuddenBurst() {
        // Sliding window does not allow sudden bursting beyond the limit.
        // If the limit is 10, no matter what, we can never execute 11 requests within the window.
        for (int i = 0; i < 10; i++) {
            assertTrue(slidingWindowAlgorithm.tryConsume(key, 10, 60000).isAllowed());
        }
        assertFalse(slidingWindowAlgorithm.tryConsume(key, 10, 60000).isAllowed());
    }

    @Test
    void tryConsume_concurrent_noRaceCondition() throws InterruptedException {
        int threadsCount = 50;
        int limit = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadsCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadsCount);
        java.util.concurrent.atomic.AtomicInteger allowedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger rejectedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadsCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    SlidingWindowResult res = slidingWindowAlgorithm.tryConsume(key, limit, 10000);
                    if (res.isAllowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(limit, allowedCount.get());
        assertEquals(threadsCount - limit, rejectedCount.get());
    }

    @Test
    void resetWindow_clearsState() {
        assertTrue(slidingWindowAlgorithm.tryConsume(key, 5, 5000).isAllowed());
        slidingWindowAlgorithm.resetWindow(key);

        List<Long> timestamps = slidingWindowAlgorithm.getRequestTimestamps(key, 5000);
        assertTrue(timestamps.isEmpty());
        assertEquals(0, slidingWindowAlgorithm.getRequestCount(key, 5000));
    }

    @Test
    void getMemoryUsage_returnsPositive() {
        assertTrue(slidingWindowAlgorithm.tryConsume(key, 5, 5000).isAllowed());
        long memory = slidingWindowAlgorithm.getMemoryUsage(key);
        assertTrue(memory >= 0, "Memory usage should be non-negative");
    }
}
