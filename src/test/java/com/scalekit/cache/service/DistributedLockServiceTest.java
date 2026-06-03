package com.scalekit.cache.service;

import com.scalekit.cache.dto.LockResult;
import com.scalekit.common.exception.LockNotAcquiredException;
import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class DistributedLockServiceTest {

    @Autowired
    private DistributedLockService lockService;

    private String lockKey;

    @BeforeEach
    void setUp() {
        lockKey = "lock:svc:test:" + UUID.randomUUID().toString();
    }

    @Test
    void concurrent_onlyOneLockHolder() throws InterruptedException, ExecutionException {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicReference<String> acquiredValue = new AtomicReference<>(null);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    LockResult result = lockService.acquire(lockKey, 5000);
                    if (result.isAcquired()) {
                        successCount.incrementAndGet();
                        acquiredValue.set(result.getLockValue());
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one thread must acquire the lock");
        assertEquals(99, failCount.get(), "99 threads must fail to acquire the lock");

        // Release the lock
        assertNotNull(acquiredValue.get());
        boolean released = lockService.release(lockKey, acquiredValue.get());
        assertTrue(released);

        // Next acquire succeeds
        LockResult nextAcquire = lockService.acquire(lockKey, 2000);
        assertTrue(nextAcquire.isAcquired());
        lockService.release(lockKey, nextAcquire.getLockValue());
    }

    @Test
    void acquireWithRetry_eventuallySucceeds() throws Exception {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        // Grab lock in another thread and hold it for 100ms
        LockResult holdResult = lockService.acquire(lockKey, 2000);
        assertTrue(holdResult.isAcquired());

        ScheduledExecutorService holderExecutor = Executors.newSingleThreadScheduledExecutor();
        holderExecutor.schedule(() -> {
            lockService.release(lockKey, holdResult.getLockValue());
        }, 100, TimeUnit.MILLISECONDS);

        // Retry acquiring with 5 retries, 50ms delay each
        long startTime = System.currentTimeMillis();
        LockResult retryResult = lockService.acquireWithRetry(lockKey, 2000, 5, 50);
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(retryResult.isAcquired(), "Should eventually acquire lock");
        assertTrue(elapsed >= 100, "Should have waited for release");

        lockService.release(lockKey, retryResult.getLockValue());
        holderExecutor.shutdown();
    }

    @Test
    void executeWithLock_runsAction() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        String result = lockService.executeWithLock(lockKey, 2000, () -> "Hello Lock");
        assertEquals("Hello Lock", result);

        // Lock must be released automatically
        LockResult check = lockService.acquire(lockKey, 1000);
        assertTrue(check.isAcquired());
        lockService.release(lockKey, check.getLockValue());
    }

    @Test
    void executeWithLock_releasesAfterException() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        assertThrows(RuntimeException.class, () -> {
            lockService.executeWithLock(lockKey, 2000, () -> {
                throw new RuntimeException("Simulated task error");
            });
        });

        // Lock must still be released automatically
        LockResult check = lockService.acquire(lockKey, 1000);
        assertTrue(check.isAcquired());
        lockService.release(lockKey, check.getLockValue());
    }

    @Test
    void watchdog_extendsLockAutomatically() throws InterruptedException {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        // Lock with 300ms TTL. Watchdog should renew it if we hold it.
        LockResult result = lockService.acquire(lockKey, 300);
        assertTrue(result.isAcquired());

        lockService.startWatchdog(lockKey, result.getLockValue(), 300);

        // Sleep 500ms. If watchdog works, lock should still be active
        Thread.sleep(500);

        // Try to acquire in another thread. Should fail since watchdog kept it alive!
        LockResult otherAcquire = lockService.acquire(lockKey, 200);
        assertFalse(otherAcquire.isAcquired(), "Lock should still be held due to watchdog renewal");

        // Stop watchdog and release
        lockService.stopWatchdog(lockKey);
        lockService.release(lockKey, result.getLockValue());
    }
}
