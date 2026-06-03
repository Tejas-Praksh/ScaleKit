package com.scalekit.cache.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.scalekit.cache.dto.CacheStats;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread‑safe LRU cache implementation with O(1) get/put using a doubly linked list
 * and a {@link HashMap}. Supports eviction callbacks and cache statistics.
 *
 * @param <K> key type – must implement {@link java.lang.Object#hashCode()} and {@link java.lang.Object#equals(Object)}
 * @param <V> value type
 */
@Slf4j
public class LRUCache<K, V> {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Node<K, V> {
        private K key;
        private V value;
        private Node<K, V> prev;
        private Node<K, V> next;
        private long lastAccessTime;
        private long accessCount;
        private long createdAt;
        private int sizeBytes; // optional, not used in core logic
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head; // dummy head (most recent side)
    private final Node<K, V> tail; // dummy tail (least recent side)
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private Consumer<Node<K, V>> evictionCallback = null;

    /**
     * Creates an LRU cache with the given capacity.
     *
     * @param capacity maximum number of entries the cache can hold
     */
    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.head = new Node<>();
        this.tail = new Node<>();
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves the value for the given key, moving the entry to the front (MRU).
     */
    public V get(K key) {
        Objects.requireNonNull(key, "Key cannot be null");
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                misses.incrementAndGet();
                return null;
            }
            moveToFront(node);
            hits.incrementAndGet();
            node.lastAccessTime = System.currentTimeMillis();
            node.accessCount++;
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts or updates a cache entry.
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "Key cannot be null");
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToFront(node);
                return;
            }
            if (map.size() >= capacity) {
                evictLRU();
            }
            Node<K, V> newNode = Node.<K, V>builder()
                    .key(key)
                    .value(value)
                    .createdAt(System.currentTimeMillis())
                    .lastAccessTime(System.currentTimeMillis())
                    .accessCount(0)
                    .sizeBytes(0)
                    .build();
            addToFront(newNode);
            map.put(key, newNode);
            puts.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns true if the cache contains the key.
     */
    public boolean containsKey(K key) {
        lock.readLock().lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes the entry for the given key, returning its value if present.
     */
    public V remove(K key) {
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) {
                return null;
            }
            removeNode(node);
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clears the cache.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
            hits.set(0);
            misses.set(0);
            evictions.set(0);
            puts.set(0);
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
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the keys in MRU → LRU order.
     */
    public List<K> getKeys() {
        lock.readLock().lock();
        try {
            List<K> keys = new ArrayList<>(map.size());
            Node<K, V> cur = head.next;
            while (cur != tail) {
                keys.add(cur.key);
                cur = cur.next;
            }
            return keys;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns entries in MRU → LRU order.
     */
    public List<Map.Entry<K, V>> getEntries() {
        lock.readLock().lock();
        try {
            List<Map.Entry<K, V>> entries = new ArrayList<>(map.size());
            Node<K, V> cur = head.next;
            while (cur != tail) {
                entries.add(new AbstractMap.SimpleImmutableEntry<>(cur.key, cur.value));
                cur = cur.next;
            }
            return entries;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the least‑recently used key.
     */
    public K getLRUKey() {
        lock.readLock().lock();
        try {
            return (tail.prev != head) ? tail.prev.key : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the most‑recently used key.
     */
    public K getMRUKey() {
        lock.readLock().lock();
        try {
            return (head.next != tail) ? head.next.key : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Registers a callback that will be invoked when an entry is evicted.
     */
    public void setEvictionCallback(Consumer<Node<K, V>> callback) {
        this.evictionCallback = callback;
    }

    /**
     * Returns cache statistics.
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            long total = hits.get() + misses.get();
            double hitRate = total == 0 ? 0.0 : (double) hits.get() / total;
            double fillRate = (double) map.size() / capacity;
            return CacheStats.builder()
                    .capacity(capacity)
                    .currentSize(map.size())
                    .hits(hits.get())
                    .misses(misses.get())
                    .evictions(evictions.get())
                    .puts(puts.get())
                    .hitRate(hitRate)
                    .fillRate(fillRate)
                    .mostRecentKey(getMRUKey())
                    .leastRecentKey(getLRUKey())
                    .measuredAt(Instant.now())
                    .build();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------
    // Private helpers – called only while holding the appropriate lock
    // ---------------------------------------------------------------------

    private void moveToFront(Node<K, V> node) {
        removeNode(node);
        addToFront(node);
    }

    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // Help GC
        node.prev = null;
        node.next = null;
    }

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru == head) {
            return; // empty
        }
        removeNode(lru);
        map.remove(lru.key);
        evictions.incrementAndGet();
        if (evictionCallback != null) {
            try {
                evictionCallback.accept(lru);
            } catch (Exception e) {
                log.warn("Eviction callback threw an exception", e);
            }
        }
    }
}
