package com.scalekit.cache.controller;

import com.scalekit.cache.algorithm.RedlockAlgorithm;
import com.scalekit.cache.dto.*;
import com.scalekit.cache.service.DistributedLockService;
import com.scalekit.cache.service.FencingTokenValidator;
import com.scalekit.cache.service.LockMonitorService;
import com.scalekit.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller exposing endpoints to acquire, release, and extend distributed locks.
 * Exposes live monitoring telemetry, audit logs, and diagnostic demonstrations.
 */
@RestController
@RequestMapping("/api/v1/locks")
@Tag(name = "Distributed Lock Manager", description = "Endpoints for managing distributed locks and checking concurrency health")
@Slf4j
public class DistributedLockController {

    private final DistributedLockService lockService;
    private final LockMonitorService monitorService;
    private final FencingTokenValidator fencingTokenValidator;
    private final RedlockAlgorithm redlockAlgorithm;

    @Autowired
    public DistributedLockController(DistributedLockService lockService,
                                     LockMonitorService monitorService,
                                     FencingTokenValidator fencingTokenValidator,
                                     RedlockAlgorithm redlockAlgorithm) {
        this.lockService = lockService;
        this.monitorService = monitorService;
        this.fencingTokenValidator = fencingTokenValidator;
        this.redlockAlgorithm = redlockAlgorithm;
    }

    @PostMapping("/acquire")
    @Operation(summary = "Acquire a distributed lock with optional retry backoff")
    public ApiResponse<LockResult> acquire(@RequestBody LockAcquireRequest request) {
        long start = System.currentTimeMillis();
        LockResult result;
        if (request.getMaxRetries() != null && request.getMaxRetries() > 0) {
            long delay = request.getRetryDelayMs() != null ? request.getRetryDelayMs() : 100;
            result = lockService.acquireWithRetry(request.getLockKey(), request.getTtlMs(), request.getMaxRetries(), delay);
        } else {
            result = lockService.acquire(request.getLockKey(), request.getTtlMs());
        }
        return ApiResponse.success(result, "Acquisition attempt complete", System.currentTimeMillis() - start);
    }

    @PostMapping("/release")
    @Operation(summary = "Release a distributed lock using its unique token value")
    public ApiResponse<Boolean> release(@RequestBody LockReleaseRequest request) {
        long start = System.currentTimeMillis();
        boolean released = lockService.release(request.getLockKey(), request.getLockValue());
        return ApiResponse.success(released, released ? "Lock released successfully" : "Failed to release lock", System.currentTimeMillis() - start);
    }

    @PostMapping("/extend")
    @Operation(summary = "Extend the TTL of an active lock")
    public ApiResponse<Boolean> extend(@RequestBody LockExtendRequest request) {
        long start = System.currentTimeMillis();
        boolean extended = redlockAlgorithm.extend(request.getLockKey(), request.getLockValue(), request.getAdditionalTtlMs());
        return ApiResponse.success(extended, extended ? "Lock TTL extended successfully" : "Failed to extend lock TTL", System.currentTimeMillis() - start);
    }

    @GetMapping("/active")
    @Operation(summary = "Get list of active distributed locks in the application context")
    public ApiResponse<List<LockInfo>> active() {
        return ApiResponse.success(lockService.getActiveLocks(), "Active locks fetched");
    }

    @GetMapping("/stats")
    @Operation(summary = "Get active lock stats and deadlock checks")
    public ApiResponse<LockStats> stats() {
        return ApiResponse.success(monitorService.getLockStats(), "Lock statistics fetched");
    }

    @GetMapping("/audit")
    @Operation(summary = "Get lock audit events")
    public ApiResponse<List<LockAuditEvent>> audit(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(monitorService.getAuditLog(limit), "Lock audit trail fetched");
    }

    @PostMapping("/demo/race-condition")
    @Operation(summary = "Demonstrate lost updates with and without distributed locking under high concurrency")
    public ApiResponse<RaceConditionDemo> raceConditionDemo(@RequestBody RaceDemoRequest request) {
        int threads = request.getThreads() > 0 ? request.getThreads() : 10;
        int iterations = request.getIterations() > 0 ? request.getIterations() : 100;
        int expected = threads * iterations;

        // 1. Simulating WITHOUT LOCKS (lost updates)
        UnsafeCounter unsafeCounter = new UnsafeCounter();
        ExecutorService executorWithout = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatchWithout = new CountDownLatch(1);
        CountDownLatch finishLatchWithout = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executorWithout.submit(() -> {
                try {
                    startLatchWithout.await();
                    for (int j = 0; j < iterations; j++) {
                        unsafeCounter.increment();
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatchWithout.countDown();
                }
            });
        }
        startLatchWithout.countDown();
        try {
            finishLatchWithout.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executorWithout.shutdown();

        // 2. Simulating WITH LOCKS
        SafeCounter safeCounter = new SafeCounter(lockService, "demo:race:counter");
        ExecutorService executorWith = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatchWith = new CountDownLatch(1);
        CountDownLatch finishLatchWith = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executorWith.submit(() -> {
                try {
                    startLatchWith.await();
                    for (int j = 0; j < iterations; j++) {
                        safeCounter.increment();
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatchWith.countDown();
                }
            });
        }
        startLatchWith.countDown();
        try {
            finishLatchWith.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executorWith.shutdown();

        int lostUpdates = expected - unsafeCounter.getCount();
        RaceConditionDemo demoResult = RaceConditionDemo.builder()
                .threads(threads)
                .iterationsPerThread(iterations)
                .expectedFinal(expected)
                .actualWithoutLock(unsafeCounter.getCount())
                .actualWithLock(safeCounter.getCount())
                .lostUpdatesWithoutLock(lostUpdates)
                .lockingFixed(safeCounter.getCount() == expected)
                .explanation("Without distributed locking, multi-threaded increments cause lost updates due to non-atomic read-modify-write operations. With Redlock, updates are fully coordinated.")
                .build();

        return ApiResponse.success(demoResult, "Race condition simulation completed");
    }

    @PostMapping("/demo/fencing")
    @Operation(summary = "Demonstrate split-brain write rejection using monotonically increasing fencing tokens")
    public ApiResponse<FencingDemo> fencingDemo() {
        String resource = "demo:fencing:row-42";
        fencingTokenValidator.reset(resource);

        long token1 = 100L;
        boolean success1 = fencingTokenValidator.validate(resource, token1);

        long token2 = 101L;
        boolean success2 = fencingTokenValidator.validate(resource, token2);

        // Stale client resumes after GC pause and attempts write with old token
        long staleToken = 99L;
        boolean successStale = fencingTokenValidator.validate(resource, staleToken);

        FencingDemo demo = FencingDemo.builder()
                .resource(resource)
                .firstToken(token1)
                .firstWriteSuccess(success1)
                .secondToken(token2)
                .secondWriteSuccess(success2)
                .staleToken(staleToken)
                .staleWriteSuccess(successStale)
                .explanation("Monotonically increasing fencing tokens allow valid successive updates (100 -> 101). Stale token (99) is rejected as it is smaller than the last seen token, preventing split-brain database overrides.")
                .build();

        return ApiResponse.success(demo, "Fencing token demonstration completed");
    }

    // Input Request Payloads
    @Data
    public static class LockAcquireRequest {
        private String lockKey;
        private long ttlMs;
        private Integer maxRetries;
        private Long retryDelayMs;
    }

    @Data
    public static class LockReleaseRequest {
        private String lockKey;
        private String lockValue;
    }

    @Data
    public static class LockExtendRequest {
        private String lockKey;
        private String lockValue;
        private long additionalTtlMs;
    }

    @Data
    public static class RaceDemoRequest {
        private int threads;
        private int iterations;
    }

    // Incrementor Helpers
    private static class UnsafeCounter {
        private int count = 0;

        public void increment() {
            int temp = count;
            try {
                // Emphasize concurrency clash
                Thread.sleep(0, 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            count = temp + 1;
        }

        public int getCount() {
            return count;
        }
    }

    private static class SafeCounter {
        private final DistributedLockService lockService;
        private final String lockKey;
        private int count = 0;

        public SafeCounter(DistributedLockService lockService, String lockKey) {
            this.lockService = lockService;
            this.lockKey = lockKey;
        }

        public void increment() {
            lockService.executeWithLock(lockKey, 10000, () -> {
                int temp = count;
                try {
                    Thread.sleep(0, 100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                count = temp + 1;
                return count;
            });
        }

        public int getCount() {
            return count;
        }
    }
}
