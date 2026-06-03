package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.CacheStats;
import com.scalekit.cache.dto.FrequencyHistogram;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * LFU (Least Frequently Used) cache with O(1) get/put operations.
 * <p>
 * Uses three hash maps:
 * <ul>
 *   <li>keyToValue: maps key to its value</li>
 *   <li>keyToFreq: maps key to its current access frequency</li>
 *   <li>freqToKeys: maps a frequency to an ordered set of keys (LinkedHashSet) –
 *       maintains insertion order for LRU tie‑breaking among keys with the same frequency.</li>
 * </ul>
 * A global {@code minFreq} tracks the smallest frequency present in the cache.
 * All public methods acquire the appropriate lock to guarantee thread‑safety.
 * </p>
 *
 * @param <K> key type (must have proper hashCode/equals)
 * @param <V> value type
 */
@Slf4j
public class LFUCache<K, V> {

    private final int capacity;
    private final Map<K, V> keyToValue = new HashMap<>();
    private final Map<K, Integer> keyToFreq = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> freqToKeys = new HashMap<>();
    private int minFreq = 0;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private Consumer<K> evictionCallback = null;

    /**
     * Creates a new LFU cache with the specified capacity.
     */
    public LFUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Retrieves the value for the given key, or {@code null} if not present.
     * This operation increments the key's access frequency.
     */
    public V get(K key) {
        lock.writeLock().lock();
        try {
            if (!keyToValue.containsKey(key)) {
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            incrementFrequency(key);
            return keyToValue.get(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts or updates a key/value pair.
     */
    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            if (capacity <= 0) {
                return; // no‑op if capacity disabled
            }
            if (keyToValue.containsKey(key)) {
                // Update value and bump frequency
                keyToValue.put(key, value);
                incrementFrequency(key);
                return;
            }
            // New entry
            if (keyToValue.size() >= capacity) {
                evictLFU();
            }
            keyToValue.put(key, value);
            keyToFreq.put(key, 1);
            freqToKeys.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
            minFreq = 1;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns {@code true} if the cache contains the specified key.
     */
    public boolean containsKey(K key) {
        lock.readLock().lock();
        try {
            return keyToValue.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes the entry for the given key and returns its value, or {@code null}.
     */
    public V remove(K key) {
        lock.writeLock().lock();
        try {
            V old = keyToValue.remove(key);
            if (old == null) {
                return null;
            }
            int freq = keyToFreq.remove(key);
            LinkedHashSet<K> set = freqToKeys.get(freq);
            if (set != null) {
                set.remove(key);
                if (set.isEmpty()) {
                    freqToKeys.remove(freq);
                    if (minFreq == freq) {
                        // recompute minFreq lazily
                        minFreq = freqToKeys.keySet().stream().min(Integer::compareTo).orElse(0);
                    }
                }
            }
            return old;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clears the entire cache and resets statistics.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            keyToValue.clear();
            keyToFreq.clear();
            freqToKeys.clear();
            minFreq = 0;
            hits.set(0);
            misses.set(0);
            evictions.set(0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Current number of entries.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return keyToValue.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a copy of the key‑to‑frequency map.
     */
    public Map<K, Integer> getFrequencies() {
        lock.readLock().lock();
        try {
            return new HashMap<>(keyToFreq);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a copy of the frequency‑to‑keys buckets.
     */
    public Map<Integer, Set<K>> getFreqBuckets() {
        lock.readLock().lock();
        try {
            Map<Integer, Set<K>> copy = new HashMap<>();
            for (Map.Entry<Integer, LinkedHashSet<K>> e : freqToKeys.entrySet()) {
                copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            }
            return copy;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Generates a histogram summarising the frequency distribution.
     */
    public FrequencyHistogram getHistogram() {
        lock.readLock().lock();
        try {
            Map<Integer, Integer> freqToCount = new HashMap<>();
            int total = 0;
            int max = 0;
            int min = Integer.MAX_VALUE;
            for (int freq : keyToFreq.values()) {
                freqToCount.merge(freq, 1, Integer::sum);
                total++;
                max = Math.max(max, freq);
                min = Math.min(min, freq);
            }
            double avg = total == 0 ? 0.0 : (double) total / freqToCount.size();
            List<FrequencyHistogram.FrequencyBucket> buckets = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : freqToCount.entrySet()) {
                FrequencyHistogram.FrequencyBucket bucket = FrequencyHistogram.FrequencyBucket.builder()
                        .frequency(e.getKey())
                        .keyCount(e.getValue())
                        .percentage(((double) e.getValue() / total) * 100)
                        .sampleKeys(collectSampleKeys(e.getKey()))
                        .build();
                buckets.add(bucket);
            }
            return FrequencyHistogram.builder()
                    .freqToCount(freqToCount)
                    .maxFrequency(max)
                    .minFrequency(min == Integer.MAX_VALUE ? 0 : min)
                    .avgFrequency(avg)
                    .totalKeys(total)
                    .buckets(buckets)
                    .build();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<String> collectSampleKeys(int freq) {
        List<String> samples = new ArrayList<>();
        LinkedHashSet<K> set = freqToKeys.get(freq);
        if (set != null) {
            int i = 0;
            for (K k : set) {
                if (i >= 5) break;
                samples.add(k.toString());
                i++;
            }
        }
        return samples;
    }

    /**
     * Returns cache statistics.
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            long total = hits.get() + misses.get();
            double hitRate = total == 0 ? 0.0 : (double) hits.get() / total;
            double fillRate = capacity == 0 ? 0.0 : (double) keyToValue.size() / capacity;
            return CacheStats.builder()
                    .capacity(capacity)
                    .currentSize(keyToValue.size())
                    .hits(hits.get())
                    .misses(misses.get())
                    .evictions(evictions.get())
                    .puts(0L) // puts not separately tracked here
                    .hitRate(hitRate)
                    .fillRate(fillRate)
                    .mostRecentKey(null)
                    .leastRecentKey(null)
                    .measuredAt(Instant.now())
                    .build();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Registers a callback to be executed when an entry is evicted.
     */
    public void setEvictionCallback(Consumer<K> callback) {
        this.evictionCallback = callback;
    }

    /**
     * Returns the current minimum frequency among all entries.
     */
    public int getMinFrequency() {
        lock.readLock().lock();
        try {
            return minFreq;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------
    // Private helper methods
    // ---------------------------------------------------------------------

    private void incrementFrequency(K key) {
        int freq = keyToFreq.get(key);
        int newFreq = freq + 1;
        keyToFreq.put(key, newFreq);

        // Remove from old bucket
        LinkedHashSet<K> oldSet = freqToKeys.get(freq);
        if (oldSet != null) {
            oldSet.remove(key);
            if (oldSet.isEmpty()) {
                freqToKeys.remove(freq);
                if (minFreq == freq) {
                    minFreq = newFreq; // next min may be higher
                }
            }
        }

        // Add to new bucket
        freqToKeys.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
    }

    private void evictLFU() {
        LinkedHashSet<K> keys = freqToKeys.get(minFreq);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // LRU tie‑breaking: first element in LinkedHashSet
        K evictKey = keys.iterator().next();
        keys.remove(evictKey);
        if (keys.isEmpty()) {
            freqToKeys.remove(minFreq);
        }
        V evictedValue = keyToValue.remove(evictKey);
        keyToFreq.remove(evictKey);
        evictions.incrementAndGet();
        if (evictionCallback != null) {
            try {
                evictionCallback.accept(evictKey);
            } catch (Exception e) {
                log.warn("Eviction callback threw an exception", e);
            }
        }
    }
}
