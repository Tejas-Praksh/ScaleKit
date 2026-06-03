package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LFUCacheTest {

    private LFUCache<String, String> cache;
    private static final int CAPACITY = 3;

    @BeforeEach
    void setUp() {
        cache = new LFUCache<>(CAPACITY);
    }

    @Test
    void get_nonExisting_returnsNullAndMisses() {
        assertNull(cache.get("missing"));
        CacheStats stats = cache.getStats();
        assertEquals(1, stats.getMisses());
        assertEquals(0, stats.getHits());
    }

    @Test
    void put_and_get_existingKey_updatesValue_andIncrementsFrequency() {
        cache.put("A", "v1");
        cache.put("A", "v2"); // update same key
        assertEquals("v2", cache.get("A"));
        Map<String, Integer> freqs = cache.getFrequencies();
        // put("A","v1") freq=1, put("A","v2") bumps to 2, get("A") bumps to 3
        assertEquals(3, freqs.get("A"));
    }

    @Test
    void put_atCapacity_evictsLFU_withLRUtieBreaking() {
        // Fill cache
        cache.put("A", "vA"); // freq 1
        cache.put("B", "vB"); // freq 1
        cache.put("C", "vC"); // freq 1
        // Access A twice, B once, C never -> freq A=2, B=1, C=1
        cache.get("A"); // A freq 2
        cache.get("B"); // B freq 2 (now both A and B 2)
        // Add D, should evict C (freq 1, LRU among freq 1)
        cache.put("D", "vD");
        assertNull(cache.get("C"), "C should be evicted as LFU");
        assertNotNull(cache.get("A"));
        assertNotNull(cache.get("B"));
        assertNotNull(cache.get("D"));
        assertEquals(1, cache.getStats().getEvictions());
    }

    @Test
    void evictionCallback_isInvoked() {
        final boolean[] called = {false};
        cache.setEvictionCallback(key -> called[0] = true);
        cache.put("X", "vX");
        cache.put("Y", "vY");
        cache.put("Z", "vZ"); // full
        cache.put("W", "vW"); // evict one
        assertTrue(called[0]);
    }

    @Test
    void clear_resetsCacheAndStats() {
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
    void getFrequencies_reflectsCorrectCounts() {
        cache.put("A", "vA");
        cache.put("B", "vB");
        cache.get("A"); // A freq 2
        cache.get("A"); // A freq 3
        cache.get("B"); // B freq 2
        Map<String, Integer> freqs = cache.getFrequencies();
        assertEquals(3, freqs.get("A"));
        assertEquals(2, freqs.get("B"));
    }

    @Test
    void minFreq_updatesCorrectlyAfterEviction() {
        cache.put("A", "vA"); // freq1
        cache.put("B", "vB"); // freq1
        cache.put("C", "vC"); // freq1
        cache.get("A"); // A freq2, minFreq still 1
        // Evict should remove B or C (both freq1, LRU)
        cache.put("D", "vD");
        assertEquals(1, cache.getMinFrequency());
    }
}
