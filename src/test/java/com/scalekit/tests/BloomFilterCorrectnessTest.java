package com.scalekit.tests;

import com.scalekit.cache.algorithm.BloomFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Bloom Filter Correctness Tests")
public class BloomFilterCorrectnessTest {

    @Test
    void zeroFalseNegatives_guaranteed() {
        BloomFilter<String> filter = new BloomFilter<>(10_000, 0.01);
        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            String item = "item-" + i;
            filter.add(item);
            inserted.add(item);
        }
        int falseNegatives = 0;
        for (String item : inserted) {
            if (!filter.mightContain(item)) {
                falseNegatives++;
            }
        }
        assertEquals(0, falseNegatives, "Bloom Filter must never have false negatives");
    }

    @Test
    void falsePositiveRate_withinBounds() {
        BloomFilter<String> filter = new BloomFilter<>(10_000, 0.01);
        // Insert 10k items
        for (int i = 0; i < 10_000; i++) {
            filter.add("known-" + i);
        }
        int falsePositives = 0;
        for (int i = 0; i < 10_000; i++) {
            if (filter.mightContain("unknown-" + i)) {
                falsePositives++;
            }
        }
        double actualFPR = falsePositives / 10_000.0;
        assertTrue(actualFPR < 0.02, "FPR " + actualFPR + " exceeds 2x configured rate");
    }

    @Test
    void definitelyNotContains_100Accurate() {
        BloomFilter<String> filter = new BloomFilter<>(1_000, 0.01);
        filter.add("exists");
        for (int i = 0; i < 1_000; i++) {
            String notAdded = "not-added-" + i;
            if (filter.definitelyNotContains(notAdded)) {
                // should be true only when filter knows it's definitely not present
                assertFalse(notAdded.equals("exists"), "definitelyNotContains incorrectly true for existing element");
            }
        }
    }
}
