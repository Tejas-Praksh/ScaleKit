package com.scalekit.cache.algorithm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.scalekit.cache.dto.NodeAssignment;
import com.scalekit.cache.dto.RingPosition;
import com.scalekit.cache.dto.HotspotReport;
import com.scalekit.cache.dto.RebalanceReport;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.*;

/**
 * Consistent Hash Ring implementation with virtual nodes.
 *
 * - Uses a TreeMap<Integer, VirtualNode> as the ring for O(log N) lookup.
 * - Each physical node gets {@code virtualNodeCount} virtual nodes.
 * - MurmurHash3 (32‑bit) is implemented from scratch for deterministic hashing.
 * - Tracks key lookups for hotspot detection.
 */
@Component
@Slf4j
public class ConsistentHashRing {

    @Data
    @AllArgsConstructor
    public static class VirtualNode {
        private final String nodeName;
        private final int virtualNodeIndex;
        private final String virtualNodeId;
        private final int ringPosition;
    }

    private final TreeMap<Integer, VirtualNode> ring = new TreeMap<>();
    private final Map<String, List<Integer>> nodePositions = new HashMap<>();
    private final int virtualNodeCount;
    private final AtomicInteger totalKeyLookups = new AtomicInteger();
    private final Map<String, AtomicLong> nodeLookupCount = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------
    public ConsistentHashRing() {
        this(150); // default virtual nodes per physical node
    }

    public ConsistentHashRing(int virtualNodeCount) {
        this.virtualNodeCount = virtualNodeCount;
        log.info("ConsistentHashRing initialized with {} virtual nodes per physical node", virtualNodeCount);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------
    public synchronized void addNode(String nodeName) {
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        if (nodePositions.containsKey(nodeName)) {
            log.warn("Node {} already exists in the ring", nodeName);
            return;
        }
        List<Integer> positions = new ArrayList<>(virtualNodeCount);
        for (int i = 0; i < virtualNodeCount; i++) {
            String vnodeId = nodeName + "#" + i;
            int pos = murmurHash(vnodeId);
            VirtualNode vnode = new VirtualNode(nodeName, i, vnodeId, pos);
            // In the unlikely event of a hash collision, we simply overwrite –
            // TreeMap will keep the last inserted vnode for that position.
            ring.put(pos, vnode);
            positions.add(pos);
        }
        nodePositions.put(nodeName, positions);
        nodeLookupCount.putIfAbsent(nodeName, new AtomicLong());
        log.info("Added node {} with {} virtual nodes", nodeName, virtualNodeCount);
    }

    public synchronized void removeNode(String nodeName) {
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        List<Integer> positions = nodePositions.remove(nodeName);
        if (positions == null) {
            log.warn("Attempted to remove non‑existent node {}", nodeName);
            return;
        }
        for (int pos : positions) {
            ring.remove(pos);
        }
        nodeLookupCount.remove(nodeName);
        log.info("Removed node {} and its {} virtual nodes", nodeName, positions.size());
    }

    public String getNode(String key) {
        Objects.requireNonNull(key, "key must not be null");
        int hash = murmurHash(key);
        Map.Entry<Integer, VirtualNode> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry(); // wrap around
        }
        VirtualNode vnode = entry.getValue();
        totalKeyLookups.incrementAndGet();
        nodeLookupCount.computeIfAbsent(vnode.getNodeName(), n -> new AtomicLong()).incrementAndGet();
        return vnode.getNodeName();
    }

    public List<String> getNodes(String key, int replicationFactor) {
        Objects.requireNonNull(key, "key must not be null");
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("replicationFactor must be positive");
        }
        Set<String> result = new LinkedHashSet<>(replicationFactor);
        int hash = murmurHash(key);
        SortedMap<Integer, VirtualNode> tail = ring.tailMap(hash, true);
        Iterator<VirtualNode> it = Stream.concat(tail.values().stream(), ring.values().stream()).iterator();
        while (it.hasNext() && result.size() < replicationFactor) {
            VirtualNode vnode = it.next();
            result.add(vnode.getNodeName());
        }
        return new ArrayList<>(result);
    }

    public Set<String> getAllNodes() {
        return Collections.unmodifiableSet(nodePositions.keySet());
    }

    /**
     * Returns a detailed assignment for a single key.
     */
    public NodeAssignment getNodeForKey(String key) {
        int hash = murmurHash(key);
        Map.Entry<Integer, VirtualNode> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        VirtualNode vnode = entry.getValue();
        long start = System.nanoTime();
        String node = vnode.getNodeName();
        long elapsed = System.nanoTime() - start;
        return NodeAssignment.builder()
                .key(key)
                .assignedNode(node)
                .ringPosition(vnode.getRingPosition())
                .keyHash(hash)
                .virtualNodeIndex(vnode.getVirtualNodeIndex())
                .lookupTimeNanos(elapsed)
                .build();
    }

    public List<RingPosition> getRingSnapshot() {
        List<RingPosition> list = new ArrayList<>(ring.size());
        for (VirtualNode vnode : ring.values()) {
            list.add(RingPosition.builder()
                    .position(vnode.getRingPosition())
                    .nodeName(vnode.getNodeName())
                    .virtualNodeIndex(vnode.getVirtualNodeIndex())
                    .virtualNodeId(vnode.getVirtualNodeId())
                    .isVirtualNode(true)
                    .build());
        }
        return list;
    }

    public Map<String, Long> getKeyDistribution(List<String> keys) {
        Map<String, Long> map = new HashMap<>();
        for (String key : keys) {
            String node = getNode(key);
            map.merge(node, 1L, Long::sum);
        }
        return map;
    }

    /**
     * Detects hotspots where a node's lookup count exceeds the expected average
     * multiplied by {@code thresholdMultiplier}.
     */
    public List<HotspotReport> detectHotspots(double thresholdMultiplier) {
        long total = totalKeyLookups.get();
        int nodeCount = nodePositions.size();
        if (nodeCount == 0) return Collections.emptyList();
        double expected = (double) total / nodeCount;
        List<HotspotReport> reports = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> e : nodeLookupCount.entrySet()) {
            long actual = e.getValue().get();
            double loadFactor = (double) actual / expected;
            boolean isHot = loadFactor > thresholdMultiplier;
            reports.add(HotspotReport.builder()
                    .nodeName(e.getKey())
                    .actualLookups(actual)
                    .expectedLookups(expected)
                    .loadFactor(loadFactor)
                    .isHotspot(isHot)
                    .recommendation(isHot ? "Consider adding virtual nodes or rebalancing" : "OK")
                    .build());
        }
        return reports;
    }

    public RebalanceReport rebalanceCheck() {
        // Collect key counts per node based on the current lookup statistics.
        Map<String, Long> counts = new HashMap<>();
        for (Map.Entry<String, AtomicLong> e : nodeLookupCount.entrySet()) {
            counts.put(e.getKey(), e.getValue().get());
        }
        if (counts.isEmpty()) {
            return RebalanceReport.builder()
                    .keysPerNode(Collections.emptyMap())
                    .mean(0)
                    .standardDeviation(0)
                    .coefficientOfVariation(0)
                    .needsRebalancing(false)
                    .recommendation("No data to analyse")
                    .overloadedNodes(Collections.emptyList())
                    .underloadedNodes(Collections.emptyList())
                    .build();
        }
        double mean = counts.values().stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = counts.values().stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double cv = mean == 0 ? 0 : stdDev / mean;
        boolean needsRebalance = cv > 0.10; // 10% coefficient of variation threshold
        List<String> overloaded = new ArrayList<>();
        List<String> underloaded = new ArrayList<>();
        if (needsRebalance) {
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                if (e.getValue() > mean * 1.2) overloaded.add(e.getKey());
                else if (e.getValue() < mean * 0.8) underloaded.add(e.getKey());
            }
        }
        return RebalanceReport.builder()
                .keysPerNode(counts)
                .mean(mean)
                .standardDeviation(stdDev)
                .coefficientOfVariation(cv)
                .needsRebalancing(needsRebalance)
                .recommendation(needsRebalance ? "Consider redistributing virtual nodes" : "Balanced")
                .overloadedNodes(overloaded)
                .underloadedNodes(underloaded)
                .build();
    }

    // ---------------------------------------------------------------------
    // MurmurHash3 (32‑bit) implementation
    // ---------------------------------------------------------------------
    private int murmurHash(String key) {
        byte[] data = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int length = data.length;
        int h = 0x9747b28c; // seed
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int i = 0;
        while (i + 4 <= length) {
            int k = ((data[i] & 0xff)) |
                    ((data[i + 1] & 0xff) << 8) |
                    ((data[i + 2] & 0xff) << 16) |
                    ((data[i + 3] & 0xff) << 24);
            i += 4;
            k *= c1;
            k = rotl32(k, 15);
            k *= c2;
            h ^= k;
            h = rotl32(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        int k1 = 0;
        int remaining = length - i;
        if (remaining == 3) {
            k1 ^= (data[i + 2] & 0xff) << 16;
        }
        if (remaining >= 2) {
            k1 ^= (data[i + 1] & 0xff) << 8;
        }
        if (remaining >= 1) {
            k1 ^= (data[i] & 0xff);
            k1 *= c1;
            k1 = rotl32(k1, 15);
            k1 *= c2;
            h ^= k1;
        }
        h ^= length;
        h = fmix32(h);
        return h;
    }

    private int rotl32(int x, int r) {
        return (x << r) | (x >>> (32 - r));
    }

    private int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
