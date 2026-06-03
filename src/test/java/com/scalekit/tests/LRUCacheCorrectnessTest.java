package com.scalekit.tests;

import com.scalekit.cache.algorithm.LRUCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LRU Cache Correctness Tests")
public class LRUCacheCorrectnessTest {

    @Test
    void exactEvictionOrder_proved() {
        LRUCache<Integer, String> cache = new LRUCache<>(3);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        // State: [3,2,1] (MRU -> LRU)
        assertEquals(3, cache.getMRUKey());
        assertEquals(1, cache.getLRUKey());
        // Access 1 -> becomes MRU
        assertEquals("one", cache.get(1));
        // State: [1,3,2]
        assertEquals(1, cache.getMRUKey());
        assertEquals(2, cache.getLRUKey());
        // Add 4, should evict 2
        cache.put(4, "four");
        assertNull(cache.get(2));
        assertEquals(4, cache.getMRUKey());
        assertEquals(3, cache.getLRUKey());
    }

    @Test
    void capacityNeverExceeded() {
        LRUCache<Integer, Integer> cache = new LRUCache<>(100);
        for (int i = 0; i < 10_000; i++) {
            cache.put(i, i);
            assertTrue(cache.size() <= 100, "Cache exceeded capacity at i=" + i);
        }
    }

    @Test
    void evictionCallback_calledOnEvict() {
        List<Integer> evicted = new ArrayList<>();
        com.scalekit.cache.algorithm.LRUCache<Integer, Integer> cache = new com.scalekit.cache.algorithm.LRUCache<>(3);
        cache.setEvictionCallback(node -> evicted.add(node.getKey()));
        cache.put(0, 0);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        assertTrue(evicted.contains(0), "First entry should be evicted");
    }

    @Test
    void nullKey_handledGracefully() {
        com.scalekit.cache.algorithm.LRUCache<Integer, String> cache = new com.scalekit.cache.algorithm.LRUCache<>(2);
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
    }

    @Test
    void singleCapacity_alwaysEvicts() {
        com.scalekit.cache.algorithm.LRUCache<Integer, Integer> cache = new com.scalekit.cache.algorithm.LRUCache<>(1);
        cache.put(1, 1);
        cache.put(2, 2);
        assertNull(cache.get(1));
        assertEquals(2, cache.get(2));
    }
}
