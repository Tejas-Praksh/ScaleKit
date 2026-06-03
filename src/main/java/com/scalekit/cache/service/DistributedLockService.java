package com.scalekit.cache.service;

import com.scalekit.cache.algorithm.RedlockAlgorithm;
import com.scalekit.cache.dto.*;
import com.scalekit.common.exception.LockNotAcquiredException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * High-level service providing distributed lock management, watchdog TTL extensions,
 * thundering-herd retry backoffs with jitter, and exception-safe execution contexts.
 */
@Service
@Slf4j
public class DistributedLockService {

    private final RedlockAlgorithm redlock;
    private final LockMonitorService monitorService;
    private final ScheduledExecutorService watchdog;
    
    private final Map<String, LockInfo> activeLocks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> watchdogTasks = new ConcurrentHashMap<>();
    private final Random random = new Random();

    @Autowired
    public DistributedLockService(RedlockAlgorithm redlock,
                                  LockMonitorService monitorService,
                                  @Qualifier("lockWatchdogExecutor") ScheduledExecutorService watchdog) {
        this.redlock = redlock;
        this.monitorService = monitorService;
        this.watchdog = watchdog;
    }

    /**
     * Attempts to acquire a distributed lock without retrying.
     */
    public LockResult acquire(String lockKey, long ttlMs) {
        String owner = "thread-" + Thread.currentThread().getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();
        LockResult result = redlock.tryAcquire(lockKey, ttlMs, owner);
        long elapsed = System.currentTimeMillis() - startTime;

        if (result.isAcquired()) {
            LockInfo info = LockInfo.builder()
                    .lockKey(lockKey)
                    .lockValue(result.getLockValue())
                    .fencingToken(result.getFencingToken())
                    .acquiredAt(Instant.now())
                    .ttlMs(ttlMs)
                    .owner(owner)
                    .watchdogActive(false)
                    .build();
            activeLocks.put(lockKey, info);

            // Audit
            monitorService.recordEvent(LockAuditEvent.builder()
                    .lockKey(lockKey)
                    .ownerId(owner)
                    .type(LockEventType.ACQUIRED)
                    .fencingToken(result.getFencingToken())
                    .durationMs(elapsed)
                    .timestamp(Instant.now())
                    .build());
        } else {
            monitorService.recordEvent(LockAuditEvent.builder()
                    .lockKey(lockKey)
                    .ownerId(owner)
                    .type(LockEventType.FAILED)
                    .fencingToken(0L)
                    .durationMs(elapsed)
                    .timestamp(Instant.now())
                    .build());
        }

        return result;
    }

    /**
     * Acquires a lock with backoff retry and thundering herd prevention jitter.
     */
    public LockResult acquireWithRetry(String lockKey, long ttlMs, int maxRetries, long retryDelayMs) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            LockResult result = acquire(lockKey, ttlMs);
            if (result.isAcquired()) {
                return result;
            }

            if (attempt < maxRetries) {
                long jitter = random.nextLong(Math.max(1, retryDelayMs / 2));
                try {
                    Thread.sleep(retryDelayMs + jitter);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return LockResult.failed(lockKey, "Lock acquisition interrupted during retry sleep");
                }
            }
        }
        return LockResult.failed(lockKey, "Max lock acquisition retries exceeded");
    }

    /**
     * Releases a lock, stops its corresponding watchdog, and logs duration.
     */
    public boolean release(String lockKey, String lockValue) {
        LockInfo info = activeLocks.get(lockKey);
        stopWatchdog(lockKey);

        long holdDuration = 0;
        String owner = "unknown";
        long token = 0;

        if (info != null && info.getLockValue().equals(lockValue)) {
            holdDuration = System.currentTimeMillis() - info.getAcquiredAt().toEpochMilli();
            owner = info.getOwner();
            token = info.getFencingToken();
            activeLocks.remove(lockKey);
        }

        boolean released = redlock.release(lockKey, lockValue);

        if (released && info != null) {
            monitorService.recordEvent(LockAuditEvent.builder()
                    .lockKey(lockKey)
                    .ownerId(owner)
                    .type(LockEventType.RELEASED)
                    .fencingToken(token)
                    .durationMs(holdDuration)
                    .timestamp(Instant.now())
                    .build());
        }

        return released;
    }

    /**
     * Executes a Supplier under a distributed lock context.
     * Automatically handles acquisition verification, watchdog registration, and final releases.
     */
    public <T> T executeWithLock(String lockKey, long ttlMs, Supplier<T> action) {
        LockResult result = acquire(lockKey, ttlMs);
        if (!result.isAcquired()) {
            throw new LockNotAcquiredException(lockKey, "Lock " + lockKey + " could not be acquired for execution.");
        }

        startWatchdog(lockKey, result.getLockValue(), ttlMs);
        try {
            return action.get();
        } finally {
            release(lockKey, result.getLockValue());
        }
    }

    /**
     * Executes a Supplier under a distributed lock context, returning Optional.empty() if unable to lock.
     */
    public <T> Optional<T> tryExecuteWithLock(String lockKey, long ttlMs, Supplier<T> action) {
        LockResult result = acquire(lockKey, ttlMs);
        if (!result.isAcquired()) {
            return Optional.empty();
        }

        startWatchdog(lockKey, result.getLockValue(), ttlMs);
        try {
            return Optional.ofNullable(action.get());
        } finally {
            release(lockKey, result.getLockValue());
        }
    }

    /**
     * Starts a background thread that periodically extends the lock TTL.
     */
    public void startWatchdog(String lockKey, String lockValue, long ttlMs) {
        LockInfo info = activeLocks.get(lockKey);
        if (info == null || !info.getLockValue().equals(lockValue)) {
            return;
        }

        info.setWatchdogActive(true);
        long delay = ttlMs / 3;

        ScheduledFuture<?> future = watchdog.scheduleAtFixedRate(() -> {
            try {
                LockInfo currentInfo = activeLocks.get(lockKey);
                if (currentInfo == null || !currentInfo.isWatchdogActive() || !currentInfo.getLockValue().equals(lockValue)) {
                    stopWatchdog(lockKey);
                    return;
                }

                boolean extended = redlock.extend(lockKey, lockValue, ttlMs);
                if (extended) {
                    monitorService.recordEvent(LockAuditEvent.builder()
                            .lockKey(lockKey)
                            .ownerId(currentInfo.getOwner())
                            .type(LockEventType.WATCHDOG_KICK)
                            .fencingToken(currentInfo.getFencingToken())
                            .durationMs(0)
                            .timestamp(Instant.now())
                            .build());
                } else {
                    log.warn("Watchdog failed to extend lock '{}'.", lockKey);
                    stopWatchdog(lockKey);
                }
            } catch (Exception e) {
                log.error("Exception in lock watchdog for '{}': {}", lockKey, e.getMessage());
                stopWatchdog(lockKey);
            }
        }, delay, delay, TimeUnit.MILLISECONDS);

        watchdogTasks.put(lockKey, future);
    }

    /**
     * Cancels the active watchdog schedule.
     */
    public void stopWatchdog(String lockKey) {
        LockInfo info = activeLocks.get(lockKey);
        if (info != null) {
            info.setWatchdogActive(false);
        }

        ScheduledFuture<?> future = watchdogTasks.remove(lockKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    public List<LockInfo> getActiveLocks() {
        return new ArrayList<>(activeLocks.values());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Stopping all active lock watchdogs on shutdown...");
        for (String lockKey : new ArrayList<>(watchdogTasks.keySet())) {
            stopWatchdog(lockKey);
        }
    }
}
