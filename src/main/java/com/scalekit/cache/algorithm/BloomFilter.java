package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.BloomFilterStats;
import com.scalekit.common.util.HashUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bloom Filter — a probabilistic data structure for membership testing.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>No false negatives</strong>: if {@code mightContain} returns {@code false},
 *       the item was <em>definitely</em> never added.</li>
 *   <li><strong>Possible false positives</strong>: if it returns {@code true},
 *       the item <em>might</em> have been added (with a small probability of error).</li>
 * </ul>
 *
 * <p>Uses 4 independent hash functions:
 * MurmurHash3 (seed 0), MurmurHash3 (seed 1), FNV-1a, and DJB2.
 *
 * @param <T> element type (must have a meaningful {@code toString()})
 */
@Slf4j
public class BloomFilter<T> {

    private final BitSet bitSet;
    @Getter private final int bitArraySize;       // m
    @Getter private final int hashFunctionCount;   // k
    @Getter private final int expectedInsertions;  // n
    @Getter private final double falsePositiveRate; // p

    private final AtomicInteger insertedCount = new AtomicInteger();
    private final AtomicLong positiveChecks = new AtomicLong();
    private final AtomicLong negativeChecks = new AtomicLong();
    private final AtomicLong estimatedFalsePositives = new AtomicLong();

    /**
     * Creates a Bloom Filter with optimal bit array size and hash function count.
     *
     * @param expectedInsertions number of elements expected to be inserted
     * @param falsePositiveRate  desired false positive probability (e.g. 0.001 for 0.1%)
     */
    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");
        }

        this.expectedInsertions = expectedInsertions;
        this.falsePositiveRate = falsePositiveRate;

        // Optimal bit array size: m = -(n * ln(p)) / (ln(2))^2
        this.bitArraySize = (int) Math.ceil(
                -(expectedInsertions * Math.log(falsePositiveRate))
                        / (Math.log(2) * Math.log(2)));

        // Optimal hash function count: k = (m/n) * ln(2)
        this.hashFunctionCount = Math.max(1, (int) Math.ceil(
                ((double) bitArraySize / expectedInsertions) * Math.log(2)));

        this.bitSet = new BitSet(bitArraySize);

        log.info("Bloom Filter created: size={} bits, hashFunctions={}, expectedFPR={}",
                bitArraySize, hashFunctionCount, falsePositiveRate);
    }

    /**
     * Package-private constructor for deserialization.
     */
    BloomFilter(BitSet bitSet, int bitArraySize, int hashFunctionCount,
                int expectedInsertions, double falsePositiveRate, int insertedCount) {
        this.bitSet = bitSet;
        this.bitArraySize = bitArraySize;
        this.hashFunctionCount = hashFunctionCount;
        this.expectedInsertions = expectedInsertions;
        this.falsePositiveRate = falsePositiveRate;
        this.insertedCount.set(insertedCount);
    }

    /**
     * Adds an item to the filter.
     */
    public void add(T item) {
        String serialized = item.toString();
        for (int i = 0; i < hashFunctionCount; i++) {
            int pos = getHashPosition(serialized, i);
            bitSet.set(pos);
        }
        insertedCount.incrementAndGet();
    }

    /**
     * Checks whether an item <em>might</em> be in the filter.
     *
     * @return {@code true} if the item might exist (possible false positive);
     *         {@code false} if the item was <strong>definitely never added</strong>
     */
    public boolean mightContain(T item) {
        String serialized = item.toString();
        for (int i = 0; i < hashFunctionCount; i++) {
            int pos = getHashPosition(serialized, i);
            if (!bitSet.get(pos)) {
                negativeChecks.incrementAndGet();
                return false;
            }
        }
        positiveChecks.incrementAndGet();
        return true;
    }

    /**
     * Returns {@code true} if the item was <strong>definitely never added</strong>.
     * This is 100% accurate — never produces false results.
     */
    public boolean definitelyNotContains(T item) {
        return !mightContain(item);
    }

    /**
     * Returns the current number of inserted elements.
     */
    public int getInsertedCount() {
        return insertedCount.get();
    }

    /**
     * Whether the filter is at or above 90% of its expected capacity.
     */
    public boolean isNearCapacity() {
        return insertedCount.get() >= (int) (expectedInsertions * 0.9);
    }

    /**
     * Returns the estimated current false positive rate based on fill ratio.
     */
    public double getEstimatedCurrentFPR() {
        int n = insertedCount.get();
        if (n == 0) return 0.0;
        return Math.pow(
                1 - Math.exp(-(double) hashFunctionCount * n / bitArraySize),
                hashFunctionCount);
    }

    /**
     * Returns comprehensive statistics about this filter.
     */
    public BloomFilterStats getStats() {
        int inserted = insertedCount.get();
        int setBits = bitSet.cardinality();
        double fillRatio = bitArraySize == 0 ? 0.0 : (double) setBits / bitArraySize;
        boolean nearCapacity = isNearCapacity();

        String recommendation;
        if (inserted == 0) {
            recommendation = "Filter is empty — ready for use.";
        } else if (nearCapacity) {
            recommendation = "Filter is near capacity (" + inserted + "/" + expectedInsertions
                    + "). Consider using ScalableBloomFilter to avoid FPR spikes.";
        } else {
            recommendation = "Filter is healthy. Fill ratio: "
                    + String.format("%.1f%%", fillRatio * 100);
        }

        return BloomFilterStats.builder()
                .insertedCount(inserted)
                .bitArraySize(bitArraySize)
                .hashFunctionCount(hashFunctionCount)
                .expectedInsertions(expectedInsertions)
                .configuredFPR(falsePositiveRate)
                .currentFillRatio(fillRatio)
                .estimatedCurrentFPR(getEstimatedCurrentFPR())
                .positiveChecks(positiveChecks.get())
                .negativeChecks(negativeChecks.get())
                .estimatedFalsePositives(estimatedFalsePositives.get())
                .isNearCapacity(nearCapacity)
                .recommendation(recommendation)
                .build();
    }

    /**
     * Merges another Bloom Filter into this one (bitwise OR).
     * Both filters must have the same {@code m} and {@code k}.
     */
    public void merge(BloomFilter<T> other) {
        if (this.bitArraySize != other.bitArraySize || this.hashFunctionCount != other.hashFunctionCount) {
            throw new IllegalArgumentException(
                    "Cannot merge filters with different m (" + bitArraySize + " vs " + other.bitArraySize
                            + ") or k (" + hashFunctionCount + " vs " + other.hashFunctionCount + ")");
        }
        this.bitSet.or(other.bitSet);
        this.insertedCount.addAndGet(other.insertedCount.get());
    }

    /**
     * Serializes the filter's bit array to a byte array (for Redis storage).
     */
    public byte[] serialize() {
        byte[] bitSetBytes = bitSet.toByteArray();
        // Header: 4 bytes m + 4 bytes k + 4 bytes n + 8 bytes p + 4 bytes insertedCount
        ByteBuffer buf = ByteBuffer.allocate(24 + bitSetBytes.length);
        buf.putInt(bitArraySize);
        buf.putInt(hashFunctionCount);
        buf.putInt(expectedInsertions);
        buf.putDouble(falsePositiveRate);
        buf.putInt(insertedCount.get());
        buf.put(bitSetBytes);
        return buf.array();
    }

    /**
     * Reconstructs a Bloom Filter from a serialized byte array.
     */
    @SuppressWarnings("unchecked")
    public static <T> BloomFilter<T> deserialize(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int m = buf.getInt();
        int k = buf.getInt();
        int n = buf.getInt();
        double p = buf.getDouble();
        int inserted = buf.getInt();
        byte[] bitSetBytes = new byte[buf.remaining()];
        buf.get(bitSetBytes);
        BitSet bs = BitSet.valueOf(bitSetBytes);
        return new BloomFilter<>(bs, m, k, n, p, inserted);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Hash functions
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Computes a bit-array position for the given item using the specified seed.
     *
     * <p>Hash function selection by seed:
     * <ul>
     *   <li>0 → MurmurHash3 (seed 0)</li>
     *   <li>1 → MurmurHash3 (seed 1)</li>
     *   <li>2 → FNV-1a (from scratch)</li>
     *   <li>3 → DJB2 (from scratch)</li>
     *   <li>4+ → MurmurHash3 (seed = seed value)</li>
     * </ul>
     */
    int getHashPosition(String item, int seed) {
        int hash;
        switch (seed) {
            case 0 -> hash = HashUtil.murmur3(item, 0);
            case 1 -> hash = HashUtil.murmur3(item, 1);
            case 2 -> hash = fnv1a(item);
            case 3 -> hash = djb2(item);
            default -> hash = HashUtil.murmur3(item, seed);
        }
        return Math.abs(hash) % bitArraySize;
    }

    /**
     * FNV-1a hash — implemented from scratch.
     *
     * <p>Follows the Fowler–Noll–Vo specification for 32-bit hashes.
     */
    static int fnv1a(String input) {
        int hash = 0x811c9dc5; // FNV offset basis
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193; // FNV prime
        }
        return hash;
    }

    /**
     * DJB2 hash — implemented from scratch.
     *
     * <p>Dan Bernstein's classic string hash function.
     */
    static int djb2(String input) {
        int hash = 5381;
        for (int i = 0; i < input.length(); i++) {
            hash = ((hash << 5) + hash) + input.charAt(i); // hash * 33 + c
        }
        return hash;
    }
}
