package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {

    private LRUCache<String, String> cache;
    private static final int CAPACITY = 3;

    @BeforeEach
    void setUp() {
        cache = new LRUCache<>(CAPACITY);
    }

    @Test
    void get_nonExisting_returnsNullAndMisses() {
        assertNull(cache.get("missing"));
        CacheStats stats = cache.getStats();
        assertEquals(1, stats.getMisses());
        assertEquals(0, stats.getHits());
    }

    @Test
    void put_and_get_existingKey_movesToFront() {
        cache.put("A", "valueA");
        cache.put("B", "valueB");
        assertEquals("valueA", cache.get("A")); // A becomes MRU
        List<String> keys = cache.getKeys();
        assertEquals(List.of("A", "B"), keys);
    }

    @Test
    void put_atCapacity_evictsLeastRecentlyUsed() {
        cache.put("A", "vA"); // MRU: A
        cache.put("B", "vB"); // MRU: B, LRU: A
        cache.put("C", "vC"); // MRU: C, LRU: A
        // Access A to make it MRU, B becomes LRU
        cache.get("A"); // order: A, C, B
        cache.put("D", "vD"); // should evict B
        assertNull(cache.get("B"));
        assertNotNull(cache.get("A"));
        assertNotNull(cache.get("C"));
        assertNotNull(cache.get("D"));
        CacheStats stats = cache.getStats();
        assertEquals(1, stats.getEvictions());
    }

    @Test
    void evictionCallback_isInvoked() {
        final boolean[] called = {false};
        cache.setEvictionCallback(node -> called[0] = true);
        cache.put("X", "vX");
        cache.put("Y", "vY");
        cache.put("Z", "vZ"); // full
        cache.put("W", "vW"); // should evict X (LRU)
        assertTrue(called[0]);
    }

    @Test
    void clear_removesAllEntriesAndResetsStats() {
        cache.put("A", "vA");
        cache.put("B", "vB");
        cache.get("A");
        cache.clear();
        assertEquals(0, cache.size());
        CacheStats stats = cache.getStats();
        assertEquals(0, stats.getHits());
        assertEquals(0, stats.getMisses());
        assertEquals(0, stats.getEvictions());
    }

    @Test
    void getKeys_returnsMRUtoLRUOrder() {
        cache.put("A", "vA"); // order: A
        cache.put("B", "vB"); // order: B, A
        cache.put("C", "vC"); // order: C, B, A
        cache.get("A"); // order: A, C, B
        List<String> keys = cache.getKeys();
        assertEquals(List.of("A", "C", "B"), keys);
    }

    @Test
    void getLRU_and_getMRU_keysWork() {
        cache.put("A", "vA");
        cache.put("B", "vB");
        cache.put("C", "vC");
        assertEquals("A", cache.getLRUKey());
        assertEquals("C", cache.getMRUKey());
        cache.get("A"); // A becomes MRU
        assertEquals("B", cache.getLRUKey());
        assertEquals("A", cache.getMRUKey());
    }
}
