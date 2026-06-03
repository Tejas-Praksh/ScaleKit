package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.BloomFilterStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BloomFilter} — correctness, false-positive rate, hash distribution.
 */
class BloomFilterTest {

    private BloomFilter<String> filter;

    @BeforeEach
    void setUp() {
        filter = new BloomFilter<>(10_000, 0.001); // 10K expected, 0.1% FPR
    }

    // ── Correctness ─────────────────────────────────────────────────────

    @Test
    void add_item_mightContainReturnsTrue() {
        filter.add("hello");
        assertTrue(filter.mightContain("hello"));
    }

    @Test
    void mightContain_notAdded_returnsFalse() {
        filter.add("hello");
        // "world" was never added — must return false (no false negatives)
        assertFalse(filter.mightContain("world"));
    }

    @Test
    void definitelyNotContains_notAdded_returnsTrue() {
        filter.add("apple");
        assertTrue(filter.definitelyNotContains("banana"));
    }

    @Test
    void add_manyItems_noFalseNegatives() {
        // THE MOST IMPORTANT TEST: Bloom Filters NEVER have false negatives
        int count = 10_000;
        for (int i = 0; i < count; i++) {
            filter.add("item-" + i);
        }

        int falseNegatives = 0;
        for (int i = 0; i < count; i++) {
            if (!filter.mightContain("item-" + i)) {
                falseNegatives++;
            }
        }

        assertEquals(0, falseNegatives,
                "Bloom Filters must NEVER produce false negatives. Found: " + falseNegatives);
    }

    // ── False Positive Rate ─────────────────────────────────────────────

    @Test
    void falsePositiveRate_withinExpected() {
        int insertions = 10_000;
        int checks = 10_000;

        for (int i = 0; i < insertions; i++) {
            filter.add("inserted-" + i);
        }

        int falsePositives = 0;
        for (int i = 0; i < checks; i++) {
            // These items were NEVER inserted
            if (filter.mightContain("NOT-inserted-" + i)) {
                falsePositives++;
            }
        }

        double actualFPR = (double) falsePositives / checks;
        // Allow generous margin: expect ~0.1% but accept up to 2%
        assertTrue(actualFPR < 0.02,
                "False positive rate " + String.format("%.4f", actualFPR)
                        + " exceeds 2% threshold. Expected ~0.1%");
    }

    // ── Hash Functions ──────────────────────────────────────────────────

    @Test
    void differentHashFunctions_differentPositions() {
        String item = "test-item";
        Set<Integer> positions = new HashSet<>();
        for (int seed = 0; seed < 4; seed++) {
            positions.add(filter.getHashPosition(item, seed));
        }
        // At least 2 different positions (very unlikely all 4 collide)
        assertTrue(positions.size() >= 2,
                "Expected at least 2 distinct positions from 4 hash functions, got " + positions.size());
    }

    @Test
    void hashDistribution_roughlyUniform() {
        int bucketCount = 10;
        int[] buckets = new int[bucketCount];
        int items = 10_000;

        for (int i = 0; i < items; i++) {
            int pos = filter.getHashPosition("item-" + i, 0);
            int bucket = (int) ((long) pos * bucketCount / filter.getBitArraySize());
            buckets[bucket]++;
        }

        // Each bucket should have ~1000 items. Allow 50%-200% of expected.
        int expected = items / bucketCount;
        for (int i = 0; i < bucketCount; i++) {
            assertTrue(buckets[i] > expected * 0.5 && buckets[i] < expected * 2.0,
                    "Bucket " + i + " has " + buckets[i] + " items, expected ~" + expected);
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────

    @Test
    void getStats_reflectsInsertions() {
        filter.add("a");
        filter.add("b");
        filter.add("c");

        BloomFilterStats stats = filter.getStats();
        assertEquals(3, stats.getInsertedCount());
        assertEquals(10_000, stats.getExpectedInsertions());
        assertFalse(stats.isNearCapacity());
        assertTrue(stats.getCurrentFillRatio() > 0);
    }

    @Test
    void isNearCapacity_detectsThreshold() {
        BloomFilter<String> small = new BloomFilter<>(100, 0.01);
        for (int i = 0; i < 90; i++) {
            small.add("item-" + i);
        }
        assertTrue(small.isNearCapacity());
    }

    // ── Merge ───────────────────────────────────────────────────────────

    @Test
    void merge_combinesTwoFilters() {
        BloomFilter<String> filter1 = new BloomFilter<>(1000, 0.01);
        BloomFilter<String> filter2 = new BloomFilter<>(1000, 0.01);

        filter1.add("alpha");
        filter2.add("beta");

        filter1.merge(filter2);

        assertTrue(filter1.mightContain("alpha"));
        assertTrue(filter1.mightContain("beta"));
        assertEquals(2, filter1.getInsertedCount());
    }

    @Test
    void merge_incompatibleFilters_throwsException() {
        BloomFilter<String> f1 = new BloomFilter<>(1000, 0.01);
        BloomFilter<String> f2 = new BloomFilter<>(2000, 0.01);
        assertThrows(IllegalArgumentException.class, () -> f1.merge(f2));
    }

    // ── Serialization ───────────────────────────────────────────────────

    @Test
    void serialize_deserialize_roundTrip() {
        filter.add("serialize-test-1");
        filter.add("serialize-test-2");

        byte[] data = filter.serialize();
        BloomFilter<String> restored = BloomFilter.deserialize(data);

        assertTrue(restored.mightContain("serialize-test-1"));
        assertTrue(restored.mightContain("serialize-test-2"));
        assertFalse(restored.mightContain("never-added"));
        assertEquals(filter.getBitArraySize(), restored.getBitArraySize());
        assertEquals(filter.getHashFunctionCount(), restored.getHashFunctionCount());
    }

    // ── Edge Cases ──────────────────────────────────────────────────────

    @Test
    void constructor_invalidArgs_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<>(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<>(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<>(100, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<>(100, -0.1));
    }

    @Test
    void emptyFilter_mightContain_returnsFalse() {
        assertFalse(filter.mightContain("anything"));
    }
}
