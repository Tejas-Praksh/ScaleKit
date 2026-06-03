package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.ScalableBloomFilterStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScalableBloomFilter} — auto-scaling, cross-filter lookups,
 * and the no-false-negatives guarantee across layers.
 */
class ScalableBloomFilterTest {

    private ScalableBloomFilter filter;

    @BeforeEach
    void setUp() {
        // Small initial capacity to trigger scaling quickly in tests
        filter = new ScalableBloomFilter(100, 0.01);
    }

    @Test
    void add_withinCapacity_singleFilter() {
        for (int i = 0; i < 50; i++) {
            filter.add("item-" + i);
        }
        assertEquals(1, filter.getFilterCount(), "Should still have only 1 filter layer");
        assertEquals(50, filter.getTotalInserted());
    }

    @Test
    void add_beyondCapacity_scalesUp() {
        // Insert more than 90% of initial capacity to trigger scaling
        for (int i = 0; i < 200; i++) {
            filter.add("item-" + i);
        }
        assertTrue(filter.getFilterCount() > 1,
                "Expected filter to scale beyond 1 layer, got " + filter.getFilterCount());
        assertEquals(200, filter.getTotalInserted());
    }

    @Test
    void mightContain_acrossFilters_works() {
        // Insert enough to create multiple layers
        for (int i = 0; i < 300; i++) {
            filter.add("cross-" + i);
        }
        assertTrue(filter.getFilterCount() > 1, "Should have multiple filter layers");

        // Items from earlier layers should still be found
        for (int i = 0; i < 300; i++) {
            assertTrue(filter.mightContain("cross-" + i),
                    "Item 'cross-" + i + "' should be found across filter layers");
        }
    }

    @Test
    void noFalseNegatives_afterScaling() {
        // THE CRITICAL TEST: no false negatives even after auto-scaling
        int count = 500;
        for (int i = 0; i < count; i++) {
            filter.add("scale-" + i);
        }

        int falseNegatives = 0;
        for (int i = 0; i < count; i++) {
            if (!filter.mightContain("scale-" + i)) {
                falseNegatives++;
            }
        }

        assertEquals(0, falseNegatives,
                "ScalableBloomFilter must have ZERO false negatives after scaling. Found: " + falseNegatives);
    }

    @Test
    void definitelyNotContains_nonExistent_returnsTrue() {
        filter.add("exists");
        assertTrue(filter.definitelyNotContains("does-not-exist"));
    }

    @Test
    void getStats_reflectsMultipleLayers() {
        for (int i = 0; i < 200; i++) {
            filter.add("stats-" + i);
        }

        ScalableBloomFilterStats stats = filter.getStats();
        assertTrue(stats.getTotalFilters() > 1);
        assertEquals(200, stats.getTotalInserted());
        assertTrue(stats.getTotalBitsUsed() > 0);
        assertEquals(stats.getTotalFilters(), stats.getFilterStats().size());
    }

    @Test
    void constructor_invalidArgs_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new ScalableBloomFilter(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new ScalableBloomFilter(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScalableBloomFilter(100, 1.0));
    }
}
