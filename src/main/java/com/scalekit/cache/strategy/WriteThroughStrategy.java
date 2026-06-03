package com.scalekit.cache.strategy;

import com.scalekit.cache.provider.CacheProvider;
import com.scalekit.cache.repository.KeyValueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.scalekit.cache.dto.CacheStrategyStats;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Write‑through cache strategy.
 * <p>
 *   - Writes are performed synchronously to the DB first, then to the cache.
 *   - Reads check the cache first; on miss the DB is queried and the result is cached.
 *   - Statistics are collected for monitoring.
 * </p>
 */
@Component
@Slf4j
public class WriteThroughStrategy implements CacheStrategy<String, String> {
    private final CacheProvider cacheProvider;
    private final KeyValueRepository repository;

    // Simple counters for stats
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong dbReads = new AtomicLong();
    private final AtomicLong dbWrites = new AtomicLong();
    private final AtomicLong totalReadLatencyMs = new AtomicLong();
    private final AtomicLong totalWriteLatencyMs = new AtomicLong();

    public WriteThroughStrategy(CacheProvider cacheProvider, KeyValueRepository repository) {
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
            totalReadLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
            return value;
        }
        cacheMisses.incrementAndGet();
        // DB fallback
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
        // Write to DB first – if DB fails we let the exception propagate
        repository.save(key, value);
        dbWrites.incrementAndGet();
        // Then write to cache
        try {
            cacheProvider.put(key, value);
        } catch (Exception e) {
            log.warn("Cache write failed after DB write for key {}: {}", key, e.getMessage());
            // DB is source of truth, continue
        }
        totalWriteLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public void delete(String key) {
        // Delete from DB first
        repository.deleteByKey(key);
        dbWrites.incrementAndGet(); // treat as a write operation
        // Then delete from cache
        try {
            cacheProvider.delete(key);
        } catch (Exception e) {
            log.warn("Cache delete failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public String getStrategyName() {
        return "Write‑Through";
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
