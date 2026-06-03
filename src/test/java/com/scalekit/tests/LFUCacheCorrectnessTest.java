package com.scalekit.tests;

import com.scalekit.cache.algorithm.LFUCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LFU Cache Correctness Tests")
public class LFUCacheCorrectnessTest {

    @Test
    void exactEvictionOrder_proved() {
        LFUCache<String, Integer> cache = new LFUCache<>(3);
        cache.put("a", 1); // freq=1
        cache.put("b", 2); // freq=1
        cache.put("c", 3); // freq=1
        // Increase frequencies
        cache.get("a"); // freq=2
        cache.get("a"); // freq=3
        cache.get("b"); // freq=2
        // Frequencies: a=3, b=2, c=1 (min)
        cache.put("d", 4); // should evict c
        assertNull(cache.get("c"), "c should be evicted as LFU");
    }

    @Test
    void tieBreaking_LRUAmongLFU() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);
        cache.put("a", 1); // freq=1, older
        cache.put("b", 2); // freq=1, newer
        cache.put("c", 3); // eviction: a (LRU among freq=1)
        assertNull(cache.get("a"), "a should be evicted due to LRU tie");
        assertNotNull(cache.get("b"), "b should remain");
    }

    @Test
    void frequencyTracking_accurate() {
        LFUCache<String, Integer> cache = new LFUCache<>(10);
        cache.put("key", 1);
        for (int i = 0; i < 10; i++) {
            cache.get("key");
        }
        Map<String, Integer> freqs = cache.getFrequencies();
        assertEquals(11, freqs.get("key"), "Frequency should be 1 put + 10 gets = 11");
    }
}
