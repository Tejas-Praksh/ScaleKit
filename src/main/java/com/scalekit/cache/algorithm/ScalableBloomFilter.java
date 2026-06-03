package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.BloomFilterStats;
import com.scalekit.cache.dto.ScalableBloomFilterStats;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scalable Bloom Filter — automatically grows by adding new filter layers
 * when the current layer nears capacity.
 *
 * <p>Solves the fixed-capacity limitation of a basic Bloom Filter.
 * When the active filter reaches 90% capacity, a new filter is added with
 * 2× the capacity and a tighter FPR (0.85× the previous filter's FPR).
 *
 * <p>Lookups check <strong>all</strong> filter layers — an item is considered
 * "might contain" if <em>any</em> layer reports it.
 *
 * <p>Thread-safe: mutations are {@code synchronized}; reads iterate a snapshot.
 */
@Slf4j
public class ScalableBloomFilter {

    private final List<BloomFilter<String>> filters = new ArrayList<>();
    private final int initialCapacity;
    private final double targetFPR;
    private final double scalingFactor;
    private final double tighteningRatio;
    private final AtomicInteger totalInserted = new AtomicInteger();

    /**
     * Creates a scalable bloom filter with default scaling (2×) and tightening (0.85×).
     */
    public ScalableBloomFilter(int initialCapacity, double targetFPR) {
        this(initialCapacity, targetFPR, 2.0, 0.85);
    }

    /**
     * Creates a scalable bloom filter with custom scaling and tightening ratios.
     */
    public ScalableBloomFilter(int initialCapacity, double targetFPR,
                                double scalingFactor, double tighteningRatio) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        if (targetFPR <= 0 || targetFPR >= 1) {
            throw new IllegalArgumentException("targetFPR must be in (0, 1)");
        }
        this.initialCapacity = initialCapacity;
        this.targetFPR = targetFPR;
        this.scalingFactor = scalingFactor;
        this.tighteningRatio = tighteningRatio;

        // Create the first filter layer with tightened FPR
        filters.add(new BloomFilter<>(initialCapacity, targetFPR * tighteningRatio));
        log.info("ScalableBloomFilter created: initialCapacity={}, targetFPR={}", initialCapacity, targetFPR);
    }

    /**
     * Adds an item to the active (most recent) filter layer.
     * Automatically creates a new layer if the current one is near capacity.
     */
    public synchronized void add(String item) {
        BloomFilter<String> current = filters.getLast();
        if (current.isNearCapacity()) {
            addNewFilter();
            current = filters.getLast();
        }
        current.add(item);
        totalInserted.incrementAndGet();
    }

    /**
     * Checks all filter layers. Returns {@code true} if any layer reports
     * the item might exist.
     */
    public boolean mightContain(String item) {
        // Iterate a snapshot to avoid ConcurrentModificationException
        List<BloomFilter<String>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(filters);
        }
        for (BloomFilter<String> filter : snapshot) {
            if (filter.mightContain(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the item is definitely not in any filter layer.
     */
    public boolean definitelyNotContains(String item) {
        return !mightContain(item);
    }

    /**
     * Returns the total number of filter layers.
     */
    public synchronized int getFilterCount() {
        return filters.size();
    }

    /**
     * Returns the total number of inserted items across all layers.
     */
    public int getTotalInserted() {
        return totalInserted.get();
    }

    /**
     * Returns the stats of the most recent (active) filter layer.
     */
    public synchronized BloomFilterStats getActiveFilterStats() {
        return filters.getLast().getStats();
    }

    /**
     * Returns aggregate statistics across all filter layers.
     */
    public synchronized ScalableBloomFilterStats getStats() {
        List<BloomFilterStats> layerStats = new ArrayList<>();
        long totalBits = 0;
        double overallFPR = 1.0;

        for (BloomFilter<String> filter : filters) {
            BloomFilterStats s = filter.getStats();
            layerStats.add(s);
            totalBits += s.getBitArraySize();
            // Combined FPR: 1 - product of (1 - individual FPR)
            overallFPR *= (1 - s.getEstimatedCurrentFPR());
        }
        overallFPR = 1 - overallFPR;

        return ScalableBloomFilterStats.builder()
                .filterStats(layerStats)
                .totalFilters(filters.size())
                .totalInserted(totalInserted.get())
                .overallFPR(overallFPR)
                .totalBitsUsed(totalBits)
                .build();
    }

    private void addNewFilter() {
        BloomFilter<String> last = filters.getLast();
        int newCapacity = (int) (last.getExpectedInsertions() * scalingFactor);
        double newFPR = last.getFalsePositiveRate() * tighteningRatio;
        // Clamp FPR to a minimum to avoid absurdly large bit arrays
        newFPR = Math.max(newFPR, 1e-10);
        filters.add(new BloomFilter<>(newCapacity, newFPR));
        log.info("ScalableBloomFilter: added layer {}. capacity={}, fpr={}",
                filters.size(), newCapacity, newFPR);
    }
}
