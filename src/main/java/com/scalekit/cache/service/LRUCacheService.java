package com.scalekit.cache.service;

import com.scalekit.cache.algorithm.LRUCache;
import com.scalekit.cache.dto.LRUSimulationResult;
import com.scalekit.cache.dto.CacheStats;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing multiple named LRU caches.
 */
@Service
@RequiredArgsConstructor
public class LRUCacheService {

    private static final Logger log = LoggerFactory.getLogger(LRUCacheService.class);

    // Map of cache name -> cache instance
    private final Map<String, LRUCache<String, Object>> caches = new ConcurrentHashMap<>();

    /**
     * Creates a new cache with the given name and capacity.
     * If a cache with that name already exists, it will be overwritten.
     */
    public void createCache(String name, int capacity) {
        LRUCache<String, Object> cache = new LRUCache<>(capacity);
        cache.setEvictionCallback(node -> log.info("Cache [{}] evicted key {}", name, node.getKey()));
        caches.put(name, cache);
        log.info("Created LRU cache '{}' with capacity {}", name, capacity);
    }

    /**
     * Retrieves an existing cache, or creates one with the provided capacity if absent.
     */
    public LRUCache<String, Object> getOrCreateCache(String name, int capacity) {
        return caches.computeIfAbsent(name, n -> {
            LRUCache<String, Object> c = new LRUCache<>(capacity);
            c.setEvictionCallback(node -> log.info("Cache [{}] evicted key {}", n, node.getKey()));
            return c;
        });
    }

    public Optional<Object> get(String cacheName, String key) {
        LRUCache<String, Object> cache = caches.get(cacheName);
        if (cache == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(key));
    }

    public void put(String cacheName, String key, Object value) {
        LRUCache<String, Object> cache = caches.computeIfAbsent(cacheName, n -> new LRUCache<>(100)); // default capacity 100
        cache.put(key, value);
    }

    public void remove(String cacheName, String key) {
        LRUCache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.remove(key);
        }
    }

    public void clear(String cacheName) {
        LRUCache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    public CacheStats getCacheStats(String cacheName) {
        LRUCache<String, Object> cache = caches.get(cacheName);
        if (cache == null) {
            return null;
        }
        return cache.getStats();
    }

    public Map<String, CacheStats> getAllCacheStats() {
        Map<String, CacheStats> result = new ConcurrentHashMap<>();
        caches.forEach((name, cache) -> result.put(name, cache.getStats()));
        return result;
    }

    /**
     * Simulates an LRU cache given a capacity and a sequence of key accesses.
     * Returns a {@link LRUSimulationResult} describing hits, misses and eviction order.
     */
    public LRUSimulationResult simulateLRUEviction(int capacity, java.util.List<String> accessSequence) {
        LRUCache<String, Object> simulationCache = new LRUCache<>(capacity);
        int hits = 0, misses = 0, evictions = 0;
        java.util.List<String> stateAfterEach = new java.util.ArrayList<>();
        java.util.List<Boolean> hitOrMiss = new java.util.ArrayList<>();

        for (String key : accessSequence) {
            Object existing = simulationCache.get(key);
            if (existing != null) {
                hits++;
                hitOrMiss.add(Boolean.TRUE);
            } else {
                misses++;
                hitOrMiss.add(Boolean.FALSE);
                // put a dummy value to trigger possible eviction
                simulationCache.put(key, new Object());
                // count eviction if size exceeds capacity after put
                if (simulationCache.size() > capacity) {
                    evictions++; // evict method already handled inside cache
                }
            }
            stateAfterEach.add(String.join(",", simulationCache.getKeys()));
        }

        double hitRate = (hits + misses) == 0 ? 0.0 : (double) hits / (hits + misses);
        return LRUSimulationResult.builder()
                .capacity(capacity)
                .accessSequence(accessSequence)
                .cacheStateAfterEach(stateAfterEach)
                .hitOrMiss(hitOrMiss)
                .totalHits(hits)
                .totalMisses(misses)
                .totalEvictions(evictions)
                .hitRate(hitRate)
                .explanation("Simulation completed using a simple LRUCache implementation.")
                .build();
    }
}
