package com.scalekit.cache.strategy;

import com.scalekit.cache.provider.RefreshAheadCacheProvider;
import com.scalekit.cache.repository.KeyValueRepository;
import com.scalekit.cache.dto.CacheStrategyStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refresh‑ahead strategy.
 * <p>
 *   On a GET, if the remaining TTL is below a threshold the strategy triggers an
 *   asynchronous refresh from the DB while still returning the stale (or current)
 *   value. This eliminates cache misses for hot keys.
 * </p>
 */
@Component
@Slf4j
public class RefreshAheadStrategy implements CacheStrategy<String, String> {

    private final RefreshAheadCacheProvider cacheProvider;
    private final KeyValueRepository repository;

    private final double refreshThresholdPercent = 0.2; // refresh when <20% TTL left
    private final ExecutorService refreshExecutor = Executors.newFixedThreadPool(4);
    private final Set<String> refreshingKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong dbReads = new AtomicLong();
    private final AtomicLong dbWrites = new AtomicLong();
    private final AtomicLong totalReadLatencyMs = new AtomicLong();
    private final AtomicLong totalWriteLatencyMs = new AtomicLong();

    public RefreshAheadStrategy(RefreshAheadCacheProvider cacheProvider, KeyValueRepository repository) {
        this.cacheProvider = cacheProvider;
        this.repository = repository;
    }

    @Override
    public String get(String key) {
        long start = System.nanoTime();
        reads.incrementAndGet();
        String value = cacheProvider.get(key);
        if (value != null) {
            cacheHits.incrementAndGet();
            long ttlRemaining = cacheProvider.remainingTtl(key);
            long ttlTotal = 300_000L; // same as provider default
            if (ttlRemaining > 0 && ((double) ttlRemaining / ttlTotal) < refreshThresholdPercent) {
                triggerAsyncRefresh(key);
            }
            totalReadLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
            return value;
        }
        cacheMisses.incrementAndGet();
        // Cache miss – load synchronously from DB and cache it
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
        // Write-through semantics for puts
        repository.save(key, value);
        dbWrites.incrementAndGet();
        cacheProvider.put(key, value);
        totalWriteLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public void delete(String key) {
        repository.deleteByKey(key);
        dbWrites.incrementAndGet();
        cacheProvider.delete(key);
    }

    private void triggerAsyncRefresh(String key) {
        if (!refreshingKeys.add(key)) {
            // already refreshing
            return;
        }
        refreshExecutor.submit(() -> {
            try {
                String fresh = repository.findByKey(key);
                dbReads.incrementAndGet();
                if (fresh != null) {
                    cacheProvider.put(key, fresh);
                }
                log.info("RefreshAhead async refreshed key {}", key);
            } catch (Exception e) {
                log.error("RefreshAhead failed for key {}: {}", key, e.getMessage());
            } finally {
                refreshingKeys.remove(key);
            }
        });
    }

    @Override
    public String getStrategyName() {
        return "Refresh‑Ahead";
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
                .pendingWriteBehindOps(0L)
                .measuredAt(Instant.now())
                .build();
    }
}
