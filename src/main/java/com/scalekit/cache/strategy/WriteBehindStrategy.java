package com.scalekit.cache.strategy;

import com.scalekit.cache.provider.CacheProvider;
import com.scalekit.cache.repository.KeyValueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.scalekit.cache.dto.CacheStrategyStats;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Write‑behind (write‑back) cache strategy.
 *
 * <p>
 *   Writes are cached immediately and queued for asynchronous persistence to the DB.
 *   Reads behave like a write‑through strategy (cache first, DB fallback).
 * </p>
 */
@Component
@Slf4j
public class WriteBehindStrategy implements CacheStrategy<String, String> {

    private final CacheProvider cacheProvider;
    private final KeyValueRepository repository;

    private final BlockingQueue<WriteOperation> writeQueue =
            new LinkedBlockingQueue<>(10_000);
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private final AtomicLong pendingWrites = new AtomicLong();
    private final AtomicLong droppedWrites = new AtomicLong();
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong dbReads = new AtomicLong();
    private final AtomicLong dbWrites = new AtomicLong();
    private final AtomicLong totalReadLatencyMs = new AtomicLong();
    private final AtomicLong totalWriteLatencyMs = new AtomicLong();

    public WriteBehindStrategy(CacheProvider cacheProvider, KeyValueRepository repository) {
        this.cacheProvider = cacheProvider;
        this.repository = repository;
    }

    @PostConstruct
    public void startWorker() {
        executor.scheduleWithFixedDelay(this::drainWriteQueue, 0, 100, TimeUnit.MILLISECONDS);
        log.info("WriteBehindStrategy background worker started.");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        // Drain any remaining items synchronously before shutdown
        drainWriteQueue();
        log.info("WriteBehindStrategy shutdown: pendingWrites={}, droppedWrites={}", pendingWrites.get(), droppedWrites.get());
    }

    private void drainWriteQueue() {
        List<WriteOperation> batch = new ArrayList<>();
        writeQueue.drainTo(batch, 100);
        if (batch.isEmpty()) {
            return;
        }
        for (WriteOperation op : batch) {
            try {
                if (op.getType() == OperationType.PUT) {
                    repository.save(op.getKey(), op.getValue());
                    dbWrites.incrementAndGet();
                } else if (op.getType() == OperationType.DELETE) {
                    repository.deleteByKey(op.getKey());
                    dbWrites.incrementAndGet();
                }
                pendingWrites.decrementAndGet();
            } catch (Exception e) {
                if (op.getRetryCount() < 3) {
                    op.incrementRetry();
                    writeQueue.offer(op);
                    log.warn("WriteBehind retry {} for key {}", op.getRetryCount(), op.getKey());
                } else {
                    droppedWrites.incrementAndGet();
                    log.error("WriteBehind failed after retries for key {}: {}", op.getKey(), e.getMessage());
                }
            }
        }
    }

    @Override
    public String get(String key) {
        long start = System.nanoTime();
        reads.incrementAndGet();
        String value = cacheProvider.get(key);
        if (value != null) {
            cacheHits.incrementAndGet();
            totalReadLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
            return value;
        }
        cacheMisses.incrementAndGet();
        // Fallback to DB
        value = repository.findByKey(key);
        dbReads.incrementAndGet();
        if (value != null) {
            cacheProvider.put(key, value);
        }
        totalReadLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
        return value;
    }

    @Override
    public void put(String key, String value) {
        long start = System.nanoTime();
        writes.incrementAndGet();
        // Write to cache immediately
        cacheProvider.put(key, value);
        // Queue DB write
        WriteOperation op = new WriteOperation(key, value, OperationType.PUT);
        boolean offered = writeQueue.offer(op);
        if (offered) {
            pendingWrites.incrementAndGet();
        } else {
            // Queue full – drop or handle as needed
            droppedWrites.incrementAndGet();
            log.warn("WriteBehind queue full – dropping write for key {}", key);
        }
        totalWriteLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public void delete(String key) {
        // Delete from cache immediately
        cacheProvider.delete(key);
        // Queue DB delete
        WriteOperation op = new WriteOperation(key, null, OperationType.DELETE);
        boolean offered = writeQueue.offer(op);
        if (offered) {
            pendingWrites.incrementAndGet();
        } else {
            droppedWrites.incrementAndGet();
            log.warn("WriteBehind queue full – dropping delete for key {}", key);
        }
    }

    @Override
    public String getStrategyName() {
        return "Write‑Behind";
    }

    @Override
    public CacheStrategyStats getStats() {
        long readCount = reads.get();
        long writeCount = writes.get();
        double hitRate = readCount == 0 ? 0.0 : (double) cacheHits.get() / readCount;
        double avgRead = readCount == 0 ? 0.0 : (double) totalReadLatencyMs.get() / readCount;
        double avgWrite = writeCount == 0 ? 0.0 : (double) totalWriteLatencyMs.get() / writeCount;
        return CacheStrategyStats.builder()
                .strategyName(getStrategyName())
                .reads(readCount)
                .writes(writeCount)
                .cacheHits(cacheHits.get())
                .cacheMisses(cacheMisses.get())
                .dbReads(dbReads.get())
                .dbWrites(dbWrites.get())
                .cacheHitRate(hitRate)
                .avgReadLatencyMs(avgRead)
                .avgWriteLatencyMs(avgWrite)
                .pendingWriteBehindOps(pendingWrites.get())
                .measuredAt(Instant.now())
                .build();
    }

    // ---------------------------------------------------------------------
    // Helper classes / enums
    // ---------------------------------------------------------------------
    private enum OperationType { PUT, DELETE }

    private static class WriteOperation {
        private final String key;
        private final String value; // null for DELETE
        private final OperationType type;
        private int retryCount = 0;

        WriteOperation(String key, String value, OperationType type) {
            this.key = key;
            this.value = value;
            this.type = type;
        }
        String getKey() { return key; }
        String getValue() { return value; }
        OperationType getType() { return type; }
        int getRetryCount() { return retryCount; }
        void incrementRetry() { this.retryCount++; }
    }
}
