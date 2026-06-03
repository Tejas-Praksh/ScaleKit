package com.scalekit.cache.provider;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Cache provider that stores values with an expiration timestamp.
 * Used by the RefreshAheadStrategy.
 */
@Component
public class RefreshAheadCacheProvider {
    private static class Entry {
        final String value;
        final long expireAt; // epoch millis
        Entry(String value, long expireAt) { this.value = value; this.expireAt = expireAt; }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis = 300_000L; // default 5 minutes

    public String get(String key) {
        Entry e = cache.get(key);
        if (e == null) return null;
        if (Instant.now().toEpochMilli() > e.expireAt) {
            // expired – remove and treat as miss
            cache.remove(key);
            return null;
        }
        return e.value;
    }

    public void put(String key, String value) {
        long expireAt = Instant.now().toEpochMilli() + ttlMillis;
        cache.put(key, new Entry(value, expireAt));
    }

    public void delete(String key) {
        cache.remove(key);
    }

    /**
     * Returns remaining TTL in milliseconds, or 0 if missing/expired.
     */
    public long remainingTtl(String key) {
        Entry e = cache.get(key);
        if (e == null) return 0L;
        long now = Instant.now().toEpochMilli();
        return Math.max(0L, e.expireAt - now);
    }
}
