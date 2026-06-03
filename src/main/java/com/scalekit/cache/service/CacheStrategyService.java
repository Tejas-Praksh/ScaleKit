package com.scalekit.cache.service;

import com.scalekit.cache.strategy.*;
import com.scalekit.cache.repository.KeyValueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import com.scalekit.cache.dto.CacheStrategyStats;
import com.scalekit.cache.dto.StrategyBenchmarkResult;

/**
 * Service that aggregates all cache strategy implementations and provides a unified API.
 */
@Service
@Slf4j
public class CacheStrategyService {

    private final Map<CacheStrategyType, CacheStrategy<String, String>> strategies = new EnumMap<>(CacheStrategyType.class);

    public CacheStrategyService(WriteThroughStrategy writeThrough,
                                WriteBehindStrategy writeBehind,
                                CacheAsideStrategy cacheAside,
                                ReadThroughStrategy readThrough,
                                RefreshAheadStrategy refreshAhead) {
        strategies.put(CacheStrategyType.WRITE_THROUGH, writeThrough);
        strategies.put(CacheStrategyType.WRITE_BEHIND, writeBehind);
        strategies.put(CacheStrategyType.CACHE_ASIDE, cacheAside);
        strategies.put(CacheStrategyType.READ_THROUGH, readThrough);
        strategies.put(CacheStrategyType.REFRESH_AHEAD, refreshAhead);
    }

    public String get(String key, CacheStrategyType type) {
        CacheStrategy<String, String> strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported cache strategy: " + type);
        }
        return strategy.get(key);
    }

    public void put(String key, String value, CacheStrategyType type) {
        CacheStrategy<String, String> strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported cache strategy: " + type);
        }
        strategy.put(key, value);
    }

    public void delete(String key, CacheStrategyType type) {
        CacheStrategy<String, String> strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported cache strategy: " + type);
        }
        strategy.delete(key);
    }

    public CacheStrategyStats getStats(CacheStrategyType type) {
        CacheStrategy<String, String> strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported cache strategy: " + type);
        }
        return strategy.getStats();
    }

    public Map<CacheStrategyType, CacheStrategyStats> getAllStats() {
        return strategies.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getStats()
                ));
    }

    // Placeholder for benchmark implementation – can be expanded later.
    // For now we provide a simple method that runs a fixed number of operations on each strategy.
    public StrategyBenchmarkResult benchmarkStrategies(int operations) {
        // Not implemented yet – will be filled in later when benchmark DTOs are added.
        throw new UnsupportedOperationException("Benchmark not implemented yet");
    }
}
