package com.scalekit.tests;

import com.scalekit.cache.algorithm.ConsistentHashRing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Consistent Hash Correctness Tests")
public class ConsistentHashCorrectnessTest {

    private List<String> generateKeys(int n) {
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add("key-" + i);
        }
        return keys;
    }

    @Test
    void addNode_minimalRemapping() {
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("node-1");
        ring.addNode("node-2");
        ring.addNode("node-3");
        List<String> keys = generateKeys(10_000);
        Map<String, String> before = new HashMap<>();
        for (String k : keys) {
            before.put(k, ring.getNode(k));
        }
        ring.addNode("node-4");
        int remapped = 0;
        for (String k : keys) {
            if (!ring.getNode(k).equals(before.get(k))) {
                remapped++;
            }
        }
        double percent = remapped * 100.0 / keys.size();
        assertTrue(percent < 35, "Too many keys remapped: " + percent + "%");
        assertTrue(percent > 15, "Too few keys remapped: " + percent + "%");
    }

    @Test
    void virtualNodes_evenDistribution() {
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("node-1");
        ring.addNode("node-2");
        ring.addNode("node-3");
        List<String> keys = generateKeys(100_000);
        Map<String, Long> distribution = ring.getKeyDistribution(keys);
        long total = keys.size();
        for (Map.Entry<String, Long> e : distribution.entrySet()) {
            double percent = e.getValue() * 100.0 / total;
            assertTrue(percent > 25, e.getKey() + " underloaded: " + percent + "%");
            assertTrue(percent < 42, e.getKey() + " overloaded: " + percent + "%");
        }
    }

    @Test
    void sameKey_alwaysSameNode() {
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("node-1");
        ring.addNode("node-2");
        String key = "deterministic-key";
        String first = ring.getNode(key);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, ring.getNode(key), "Same key must map to same node");
        }
    }
}
