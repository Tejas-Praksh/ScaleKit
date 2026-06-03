package com.scalekit.cache.strategy;

import com.scalekit.cache.provider.CacheProvider;
import com.scalekit.cache.repository.KeyValueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Service offering three cache‑stampede‑prevention patterns.
 */
@Service
@Slf4j
public class StampedePreventionService {

    private final CacheProvider cacheProvider;
    private final KeyValueRepository repository;

    // Mutex‑based per‑key locks
    private final Map<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    // Probabilistic early recomputation (PER) parameters
    private final double beta = 1.0; // default – can be tuned via config

    public StampedePreventionService(CacheProvider cacheProvider, KeyValueRepository repository) {
        this.cacheProvider = cacheProvider;
        this.repository = repository;
    }

    /**
     * Mutex‑based stampede protection.
     * Only one thread fetches from DB; others wait for cache to be filled.
     */
    public String getWithMutex(String key, Supplier<String> dbFetcher, long ttlSeconds) {
        String value = cacheProvider.get(key);
        if (value != null) {
            return value;
        }
        // Acquire per‑key lock
        ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double‑check after acquiring lock
            value = cacheProvider.get(key);
            if (value != null) {
                return value;
            }
            // Still miss – load from DB
            value = dbFetcher.get();
            if (value != null) {
                // Store with TTL (simplified – cacheProvider has no TTL support, so we just put)
                cacheProvider.put(key, value);
            }
            return value;
        } finally {
            lock.unlock();
            // Clean up empty locks to avoid memory leak
            keyLocks.remove(key, lock);
        }
    }

    /**
     * Probabilistic Early Refresh (PER) – stochastic early expiration.
     */
    public String getWithProbabilisticRefresh(String key, Supplier<String> dbFetcher, long ttlSeconds, double beta) {
        String value = cacheProvider.get(key);
        if (value != null) {
            // Compute early expiry time based on beta and random factor
            double rand = Math.random();
            long now = Instant.now().toEpochMilli();
            long ttlMs = ttlSeconds * 1000L;
            long earlyExpiry = now + (long) (ttlMs * (1 - beta * rand));
            // If current time passed earlyExpiry, refresh
            if (now > earlyExpiry) {
                // Refresh synchronously (could be async in production)
                value = dbFetcher.get();
                if (value != null) {
                    cacheProvider.put(key, value);
                }
                log.info("PER refreshed key {} after early expiry", key);
            }
            return value;
        }
        // Cache miss – load normally
        value = dbFetcher.get();
        if (value != null) {
            cacheProvider.put(key, value);
        }
        return value;
    }

    /**
     * Stale‑While‑Revalidate – serve stale data while refreshing async.
     */
    public String getWithStaleWhileRevalidate(String key, Supplier<String> dbFetcher, long staleTtlSeconds, long freshTtlSeconds) {
        // For simplicity, treat cacheProvider as not having TTL; we just check presence.
        // In a real implementation we would store timestamps.
        String value = cacheProvider.get(key);
        if (value != null) {
            // In real case we would check if stale, but here we always treat as fresh.
            return value;
        }
        // Miss – load synchronously and cache
        value = dbFetcher.get();
        if (value != null) {
            cacheProvider.put(key, value);
        }
        return value;
    }
}
