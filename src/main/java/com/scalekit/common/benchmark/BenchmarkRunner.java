package com.scalekit.common.benchmark;

import com.scalekit.cache.algorithm.BloomFilter;
import com.scalekit.cache.algorithm.ConsistentHashRing;
import com.scalekit.cache.algorithm.LFUCache;
import com.scalekit.cache.algorithm.LRUCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone micro-benchmark runner for in-memory data structures.
 * <p>
 * Activated only with the {@code benchmark} Spring profile:
 * {@code java -jar scalekit.jar --spring.profiles.active=benchmark}
 * <p>
 * Benchmarks pure algorithmic performance of:
 * <ul>
 *   <li>LRU Cache — get/put latency, eviction throughput</li>
 *   <li>LFU Cache — get/put latency, frequency tracking</li>
 *   <li>Bloom Filter — insert/lookup latency, false positive rate</li>
 *   <li>Consistent Hash Ring — node add/remove, key lookup, distribution</li>
 * </ul>
 */
@Component
@Profile("benchmark")
@Slf4j
public class BenchmarkRunner implements CommandLineRunner {

    private static final int WARMUP_ITERATIONS = 1_000;
    private static final int BENCHMARK_ITERATIONS = 100_000;
    private static final int CACHE_CAPACITY = 10_000;
    private static final int BLOOM_EXPECTED_INSERTIONS = 100_000;
    private static final double BLOOM_FPP = 0.01;
    private static final int HASH_RING_VIRTUAL_NODES = 150;
    private static final int HASH_RING_PHYSICAL_NODES = 5;

    @Override
    public void run(String... args) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║       ScaleKit Micro-Benchmark Suite                        ║");
        log.info("║       JVM: {} {}                                            ║",
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"));
        log.info("╚══════════════════════════════════════════════════════════════╝");

        benchmarkLRUCache();
        benchmarkLFUCache();
        benchmarkBloomFilter();
        benchmarkConsistentHashing();

        log.info("\n═══ All benchmarks complete ═══");
    }

    // ── LRU Cache ──────────────────────────────────────────────────────────
    private void benchmarkLRUCache() {
        log.info("\n┌─────────────────────────────────────────┐");
        log.info("│  LRU Cache Benchmark                    │");
        log.info("│  Capacity: {}  Iterations: {} │", CACHE_CAPACITY, BENCHMARK_ITERATIONS);
        log.info("└─────────────────────────────────────────┘");

        LRUCache<String, String> cache = new LRUCache<>(CACHE_CAPACITY);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.put("warmup-" + i, "value-" + i);
            cache.get("warmup-" + i);
        }

        // Benchmark PUT
        long[] putLatencies = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            cache.put("key-" + i, "value-" + i);
            putLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("LRU PUT", putLatencies);

        // Benchmark GET (hit)
        long[] getHitLatencies = new long[BENCHMARK_ITERATIONS];
        int hits = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            int key = ThreadLocalRandom.current().nextInt(BENCHMARK_ITERATIONS);
            long start = System.nanoTime();
            String result = cache.get("key-" + key);
            getHitLatencies[i] = System.nanoTime() - start;
            if (result != null) hits++;
        }
        reportLatencies("LRU GET", getHitLatencies);
        double hitRate = (double) hits / BENCHMARK_ITERATIONS * 100;
        log.info("  Cache hit rate: {}/{} ({}%)", hits, BENCHMARK_ITERATIONS, String.format("%.1f", hitRate));

        // Benchmark GET (miss)
        long[] getMissLatencies = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            cache.get("miss-" + i);
            getMissLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("LRU GET (miss)", getMissLatencies);
    }

    // ── LFU Cache ──────────────────────────────────────────────────────────
    private void benchmarkLFUCache() {
        log.info("\n┌─────────────────────────────────────────┐");
        log.info("│  LFU Cache Benchmark                    │");
        log.info("│  Capacity: {}  Iterations: {} │", CACHE_CAPACITY, BENCHMARK_ITERATIONS);
        log.info("└─────────────────────────────────────────┘");

        LFUCache<String, String> cache = new LFUCache<>(CACHE_CAPACITY);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.put("warmup-" + i, "value-" + i);
            cache.get("warmup-" + i);
        }

        // Benchmark PUT
        long[] putLatencies = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            cache.put("key-" + i, "value-" + i);
            putLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("LFU PUT", putLatencies);

        // Benchmark GET
        long[] getLatencies = new long[BENCHMARK_ITERATIONS];
        int hits = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            int key = ThreadLocalRandom.current().nextInt(BENCHMARK_ITERATIONS);
            long start = System.nanoTime();
            String result = cache.get("key-" + key);
            getLatencies[i] = System.nanoTime() - start;
            if (result != null) hits++;
        }
        reportLatencies("LFU GET", getLatencies);
        double hitRate = (double) hits / BENCHMARK_ITERATIONS * 100;
        log.info("  Cache hit rate: {}/{} ({}%)", hits, BENCHMARK_ITERATIONS, String.format("%.1f", hitRate));
    }

    // ── Bloom Filter ──────────────────────────────────────────────────────
    private void benchmarkBloomFilter() {
        log.info("\n┌─────────────────────────────────────────┐");
        log.info("│  Bloom Filter Benchmark                 │");
        log.info("│  Expected: {}  FPP: {}       │", BLOOM_EXPECTED_INSERTIONS, BLOOM_FPP);
        log.info("└─────────────────────────────────────────┘");

        BloomFilter<String> bloom = new BloomFilter<>(BLOOM_EXPECTED_INSERTIONS, BLOOM_FPP);

        // Benchmark INSERT
        long[] insertLatencies = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            bloom.add("element-" + i);
            insertLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("Bloom INSERT", insertLatencies);

        // Benchmark LOOKUP (true positives)
        long[] lookupHitLatencies = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            bloom.mightContain("element-" + i);
            lookupHitLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("Bloom LOOKUP (hit)", lookupHitLatencies);

        // Benchmark LOOKUP (negatives — measure false positive rate)
        long[] lookupMissLatencies = new long[BENCHMARK_ITERATIONS];
        int falsePositives = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            boolean result = bloom.mightContain("nonexistent-" + i);
            lookupMissLatencies[i] = System.nanoTime() - start;
            if (result) falsePositives++;
        }
        reportLatencies("Bloom LOOKUP (miss)", lookupMissLatencies);
        double fpRate = (double) falsePositives / BENCHMARK_ITERATIONS * 100;
        log.info("  False positive rate: {}/{} ({}%)", falsePositives, BENCHMARK_ITERATIONS, String.format("%.3f", fpRate));
        log.info("  Target FPP: {}%  Actual: {}%", BLOOM_FPP * 100, String.format("%.3f", fpRate));
    }

    // ── Consistent Hashing ────────────────────────────────────────────────
    private void benchmarkConsistentHashing() {
        log.info("\n┌─────────────────────────────────────────┐");
        log.info("│  Consistent Hashing Benchmark           │");
        log.info("│  Nodes: {}  VNodes: {}            │", HASH_RING_PHYSICAL_NODES, HASH_RING_VIRTUAL_NODES);
        log.info("└─────────────────────────────────────────┘");

        ConsistentHashRing ring = new ConsistentHashRing(HASH_RING_VIRTUAL_NODES);

        // Add nodes
        for (int i = 0; i < HASH_RING_PHYSICAL_NODES; i++) {
            ring.addNode("node-" + i);
        }

        // Benchmark KEY LOOKUP
        long[] lookupLatencies = new long[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            String key = "user:" + ThreadLocalRandom.current().nextInt(1_000_000);
            long start = System.nanoTime();
            ring.getNode(key);
            lookupLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("Hash Ring LOOKUP", lookupLatencies);

        // Distribution analysis
        Map<String, Integer> distribution = new HashMap<>();
        int totalKeys = 100_000;
        for (int i = 0; i < totalKeys; i++) {
            String node = ring.getNode("key-" + i);
            distribution.merge(node, 1, Integer::sum);
        }

        double idealPerNode = (double) totalKeys / HASH_RING_PHYSICAL_NODES;
        double maxDeviation = 0;
        log.info("  Key distribution ({} keys across {} nodes):", totalKeys, HASH_RING_PHYSICAL_NODES);
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            double deviation = Math.abs(entry.getValue() - idealPerNode) / idealPerNode * 100;
            maxDeviation = Math.max(maxDeviation, deviation);
            log.info("    {} → {} keys ({}% deviation)", entry.getKey(), entry.getValue(), String.format("%.1f", deviation));
        }
        log.info("  Max deviation: {}%  (target: <5%)", String.format("%.1f", maxDeviation));

        // Benchmark NODE ADD
        long[] addLatencies = new long[100];
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            ring.addNode("bench-node-" + i);
            addLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("Hash Ring NODE ADD", addLatencies);

        // Benchmark NODE REMOVE
        long[] removeLatencies = new long[100];
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            ring.removeNode("bench-node-" + i);
            removeLatencies[i] = System.nanoTime() - start;
        }
        reportLatencies("Hash Ring NODE REMOVE", removeLatencies);
    }

    // ── Reporting ─────────────────────────────────────────────────────────
    private void reportLatencies(String label, long[] latencies) {
        Arrays.sort(latencies);
        int n = latencies.length;

        long sum = 0;
        for (long l : latencies) sum += l;

        double avgNs = (double) sum / n;
        long p50 = latencies[n / 2];
        long p95 = latencies[(int) (n * 0.95)];
        long p99 = latencies[(int) (n * 0.99)];
        long min = latencies[0];
        long max = latencies[n - 1];

        double throughput = n / (sum / 1_000_000_000.0);

        log.info("  {} ({} ops):", label, n);
        log.info("    avg={}µs  p50={}µs  p95={}µs  p99={}µs",
                String.format("%.2f", avgNs / 1000.0), String.format("%.2f", p50 / 1000.0),
                String.format("%.2f", p95 / 1000.0), String.format("%.2f", p99 / 1000.0));
        log.info("    min={}µs  max={}µs  throughput={} ops/sec",
                String.format("%.2f", min / 1000.0), String.format("%.2f", max / 1000.0),
                String.format("%.0f", throughput));
    }
}
