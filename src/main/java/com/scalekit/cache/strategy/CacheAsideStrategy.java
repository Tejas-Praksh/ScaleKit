package com.scalekit.cache.strategy;

import com.scalekit.cache.provider.CacheProvider;
import com.scalekit.cache.repository.KeyValueRepository;
import com.scalekit.cache.dto.CacheStrategyStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache‑aside (lazy‑loading) strategy.
 * <p>
 *   - Reads first check the cache; on miss the DB is queried and the result is cached.
 *   - Writes go directly to the DB and invalidate the cache (no cache update).
 *   - Deletes remove from both DB and cache.
 * </p>
 */
@Component
@Slf4j
public class CacheAsideStrategy implements CacheStrategy<String, String> {

    private final CacheProvider cacheProvider;
    private final KeyValueRepository repository;

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong dbReads = new AtomicLong();
    private final AtomicLong dbWrites = new AtomicLong();
    private final AtomicLong totalReadLatencyMs = new AtomicLong();
    private final AtomicLong totalWriteLatencyMs = new AtomicLong();

    public CacheAsideStrategy(CacheProvider cacheProvider, KeyValueRepository repository) {
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
        // Load from DB and cache it
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
        // Write to DB first
        repository.save(key, value);
        dbWrites.incrementAndGet();
        // Invalidate cache (do not update it)
        cacheProvider.delete(key);
        totalWriteLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
        log.info("CacheAside put: key {} persisted to DB and cache invalidated", key);
    }

    @Override
    public void delete(String key) {
        // Delete from DB first
        repository.deleteByKey(key);
        dbWrites.incrementAndGet(); // treat as a write
        // Delete from cache
        cacheProvider.delete(key);
        log.info("CacheAside delete: key {} removed from DB and cache", key);
    }

    @Override
    public String getStrategyName() {
        return "Cache‑Aside";
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
