package com.scalekit.cache.strategy;
import com.scalekit.cache.dto.CacheStrategyStats;

/**
 * Interface defining the contract for cache strategies.
 *
 * @param <K> type of the cache key
 * @param <V> type of the cache value
 */
public interface CacheStrategy<K, V> {
    V get(K key);
    void put(K key, V value);
    void delete(K key);
    String getStrategyName();
    CacheStrategyStats getStats();
}
