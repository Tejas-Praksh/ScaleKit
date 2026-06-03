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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency test verifying the Token Bucket algorithm under heavy contention.
 *
 * <p>Fires 100 concurrent threads against a single bucket with capacity 50.
 * Because the Lua script runs atomically in Redis (single-threaded execution),
 * <strong>exactly</strong> 50 requests must be allowed and 50 rejected,
 * regardless of thread scheduling.
 *
 * <p>This test proves the rate limiter is safe for distributed deployments
 * where multiple application instances share the same Redis.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class ConcurrencyTest {

    @Autowired
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    private String key;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping concurrency integration test.");
        key = "tb:concurrency:" + UUID.randomUUID();
    }

    /**
     * 100 threads race to consume tokens from a bucket with capacity 50.
     * Exactly 50 must be allowed and 50 must be rejected.
     */
    @Test
    void concurrentRequests_exactCapacityEnforced() throws InterruptedException {
        int threadCount = 100;
        int bucketCapacity = 50;
        double refillRate = 0.0; // no refill during the test

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // wait for all threads to be ready
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                TokenBucketResult result = tokenBucketAlgorithm.tryConsume(
                        key, bucketCapacity, refillRate);
                if (result.isAllowed()) {
                    allowed.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                doneLatch.countDown();
            });
        }

        // Wait for all threads to be created and ready
        assertTrue(readyLatch.await(10, TimeUnit.SECONDS), "Not all threads became ready");

        // Fire all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Not all threads finished in time");
        executor.shutdown();

        // ASSERT: exactly capacity allowed, remainder rejected
        assertEquals(bucketCapacity, allowed.get(),
                "Allowed requests must exactly equal bucket capacity");
        assertEquals(threadCount - bucketCapacity, rejected.get(),
                "Rejected requests must be total - capacity");
        assertEquals(threadCount, allowed.get() + rejected.get(),
                "Total requests must equal thread count");
    }

    /**
     * Tests that a bucket with capacity 10 under 50 concurrent requests
     * allows exactly 10 and rejects 40.
     */
    @Test
    void concurrentRequests_smallBucket_strictEnforcement() throws InterruptedException {
        int threadCount = 50;
        int bucketCapacity = 10;
        double refillRate = 0.0;

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, bucketCapacity, refillRate);
                if (result.isAllowed()) {
                    allowed.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                doneLatch.countDown();
            });
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(bucketCapacity, allowed.get(),
                "Small bucket: exactly 10 requests should be allowed");
        assertEquals(threadCount - bucketCapacity, rejected.get());
    }

    /**
     * Verifies that refill works correctly under contention:
     * exhaust the bucket, wait for partial refill, then race again.
     */
    @Test
    void concurrentRequests_withRefill_allowsAfterWait() throws InterruptedException {
        int bucketCapacity = 10;
        double refillRate = 10.0; // 10 tokens/sec → full refill in 1 second

        // Phase 1: exhaust the bucket
        for (int i = 0; i < bucketCapacity; i++) {
            TokenBucketResult r = tokenBucketAlgorithm.tryConsume(key, bucketCapacity, refillRate);
            assertTrue(r.isAllowed(), "Phase 1: token " + i + " should be allowed");
        }
        // Verify empty
        assertFalse(tokenBucketAlgorithm.tryConsume(key, bucketCapacity, refillRate).isAllowed());

        // Phase 2: wait 600ms → ~6 tokens should refill
        Thread.sleep(600);

        // Phase 3: race with 20 threads
        int threadCount = 20;
        AtomicInteger allowed = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, bucketCapacity, refillRate);
                if (result.isAllowed()) {
                    allowed.incrementAndGet();
                }
                doneLatch.countDown();
            });
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Should have allowed roughly 5-7 tokens (timing-dependent)
        int allowedCount = allowed.get();
        assertTrue(allowedCount >= 4 && allowedCount <= 8,
                "Expected 4-8 tokens after 600ms refill at 10 tok/s, but got " + allowedCount);
    }

    /**
     * Multiple independent buckets (different keys) under concurrent access.
     * Each should enforce its own capacity independently.
     */
    @Test
    void concurrentRequests_multipleBuckets_independent() throws InterruptedException {
        int bucketsCount = 5;
        int threadsPerBucket = 20;
        int bucketCapacity = 10;

        AtomicInteger[] allowedPerBucket = new AtomicInteger[bucketsCount];
        String[] keys = new String[bucketsCount];
        for (int b = 0; b < bucketsCount; b++) {
            allowedPerBucket[b] = new AtomicInteger(0);
            keys[b] = "tb:multi:" + UUID.randomUUID();
        }

        int totalThreads = bucketsCount * threadsPerBucket;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        for (int b = 0; b < bucketsCount; b++) {
            int bucketIdx = b;
            for (int t = 0; t < threadsPerBucket; t++) {
                executor.submit(() -> {
                    TokenBucketResult result = tokenBucketAlgorithm.tryConsume(
                            keys[bucketIdx], bucketCapacity, 0.0);
                    if (result.isAllowed()) {
                        allowedPerBucket[bucketIdx].incrementAndGet();
                    }
                    doneLatch.countDown();
                });
            }
        }

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        for (int b = 0; b < bucketsCount; b++) {
            assertEquals(bucketCapacity, allowedPerBucket[b].get(),
                    "Bucket " + b + " should allow exactly " + bucketCapacity);
        }
    }
}
