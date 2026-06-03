package com.scalekit.cache.provider;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Simple in‑memory cache provider used for demonstration & tests.
 * In a real deployment this would wrap a RedisTemplate or similar.
 */
@Component
public class CacheProvider {
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String key) {
        return cache.get(key);
    }

    public void put(String key, String value) {
        cache.put(key, value);
    }

    public void delete(String key) {
        cache.remove(key);
    }
}
