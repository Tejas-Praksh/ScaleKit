package com.scalekit.cache.strategy;

import com.scalekit.cache.provider.CacheProvider;
import com.scalekit.cache.repository.KeyValueRepository;
import com.scalekit.cache.dto.CacheStrategyStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read‑through cache strategy.
 * <p>
 *   The application talks only to the cache. On a cache miss the strategy loads the value
 *   from the DB, stores it in the cache, and returns it.
 *   PUT operations write to both cache and DB (behaving like write‑through).
 * </p>
 */
@Component
@Slf4j
public class ReadThroughStrategy implements CacheStrategy<String, String> {

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

    public ReadThroughStrategy(CacheProvider cacheProvider, KeyValueRepository repository) {
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
        // Load from DB
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
        // Then write to cache
        cacheProvider.put(key, value);
        totalWriteLatencyMs.addAndGet((System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public void delete(String key) {
        // Delete from DB
        repository.deleteByKey(key);
        dbWrites.incrementAndGet();
        // Delete from cache
        cacheProvider.delete(key);
        log.info("ReadThrough delete: key {} removed from DB and cache", key);
    }

    @Override
    public String getStrategyName() {
        return "Read‑Through";
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
