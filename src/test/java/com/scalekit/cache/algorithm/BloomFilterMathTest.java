package com.scalekit.cache.algorithm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Bloom Filter mathematical properties: optimal parameter calculation,
 * fill‑ratio effects, and the no-false-negatives invariant.
 */
class BloomFilterMathTest {

    @Test
    void optimalSize_calculatedCorrectly() {
        // For n=1,000,000 and p=0.001:
        // m = -(1000000 * ln(0.001)) / (ln(2))^2 ≈ 14,377,588
        // k = (m/n) * ln(2) ≈ 10
        BloomFilter<String> filter = new BloomFilter<>(1_000_000, 0.001);

        // Allow ±5% tolerance due to ceiling
        int expectedM = 14_377_588;
        assertTrue(Math.abs(filter.getBitArraySize() - expectedM) < expectedM * 0.05,
                "Expected m ≈ " + expectedM + ", got " + filter.getBitArraySize());

        int expectedK = 10;
        assertTrue(Math.abs(filter.getHashFunctionCount() - expectedK) <= 1,
                "Expected k ≈ " + expectedK + ", got " + filter.getHashFunctionCount());
    }

    @Test
    void optimalSize_smallFilter() {
        // For n=100, p=0.01: m ≈ 959, k ≈ 7
        BloomFilter<String> filter = new BloomFilter<>(100, 0.01);
        assertTrue(filter.getBitArraySize() > 900 && filter.getBitArraySize() < 1100,
                "Expected m ≈ 959, got " + filter.getBitArraySize());
        assertTrue(filter.getHashFunctionCount() >= 6 && filter.getHashFunctionCount() <= 8,
                "Expected k ≈ 7, got " + filter.getHashFunctionCount());
    }

    @Test
    void fillRatio_affectsFPR() {
        BloomFilter<String> filter = new BloomFilter<>(1000, 0.01);

        // At 10% fill → very low estimated FPR
        for (int i = 0; i < 100; i++) {
            filter.add("item-" + i);
        }
        double fprAt10 = filter.getEstimatedCurrentFPR();

        // At 90% fill → higher estimated FPR
        for (int i = 100; i < 900; i++) {
            filter.add("item-" + i);
        }
        double fprAt90 = filter.getEstimatedCurrentFPR();

        // At 100%+ fill → FPR spikes
        for (int i = 900; i < 1200; i++) {
            filter.add("item-" + i);
        }
        double fprAt120 = filter.getEstimatedCurrentFPR();

        assertTrue(fprAt10 < fprAt90,
                "FPR at 10% fill (" + fprAt10 + ") should be less than at 90% (" + fprAt90 + ")");
        assertTrue(fprAt90 < fprAt120,
                "FPR at 90% fill (" + fprAt90 + ") should be less than at 120% (" + fprAt120 + ")");
    }

    @Test
    void noFalseNegatives_mathematicalProof() {
        // THEOREM: Bloom Filters NEVER have false negatives.
        //
        // PROOF: When we add item X, we set bits at positions h1(X), h2(X), ..., hk(X).
        // Bits in a BitSet are NEVER unset by any operation (only set).
        // Therefore, when we check mightContain(X), all those bits are still set → returns true.
        //
        // This test empirically verifies the theorem across multiple filter configurations.

        int[][] configs = {
                {100, 1},     // tiny
                {1000, 10},   // small
                {10000, 100}, // medium
        };
        double[] fprs = {0.1, 0.01, 0.001};

        for (int[] config : configs) {
            for (double fpr : fprs) {
                int n = config[0];
                BloomFilter<String> filter = new BloomFilter<>(n, fpr);

                // Insert n items
                for (int i = 0; i < n; i++) {
                    filter.add("proof-" + n + "-" + fpr + "-" + i);
                }

                // Verify: ALL items must be found
                for (int i = 0; i < n; i++) {
                    assertTrue(filter.mightContain("proof-" + n + "-" + fpr + "-" + i),
                            "False negative at config n=" + n + ", fpr=" + fpr + ", i=" + i);
                }
            }
        }
    }

    @Test
    void estimatedFPR_zeroWhenEmpty() {
        BloomFilter<String> filter = new BloomFilter<>(1000, 0.01);
        assertEquals(0.0, filter.getEstimatedCurrentFPR());
    }

    @Test
    void fnv1a_producesConsistentHash() {
        int hash1 = BloomFilter.fnv1a("test");
        int hash2 = BloomFilter.fnv1a("test");
        assertEquals(hash1, hash2, "FNV-1a must be deterministic");

        int hash3 = BloomFilter.fnv1a("different");
        assertNotEquals(hash1, hash3, "Different inputs should produce different hashes");
    }

    @Test
    void djb2_producesConsistentHash() {
        int hash1 = BloomFilter.djb2("test");
        int hash2 = BloomFilter.djb2("test");
        assertEquals(hash1, hash2, "DJB2 must be deterministic");

        int hash3 = BloomFilter.djb2("different");
        assertNotEquals(hash1, hash3, "Different inputs should produce different hashes");
    }
}
