package com.scalekit.ratelimiter.controller;

import com.scalekit.analytics.repository.AlgorithmBenchmarkRepository;
import com.scalekit.ratelimiter.algorithm.AdaptiveRateLimiter;
import com.scalekit.ratelimiter.algorithm.AdaptiveResult;
import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import com.scalekit.ratelimiter.dto.SystemHealthDto;
import com.scalekit.ratelimiter.service.CompleteBenchmarkService;
import com.scalekit.ratelimiter.service.FixedWindowService;
import com.scalekit.ratelimiter.service.MemoryAnalysisService;
import com.scalekit.ratelimiter.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Controller for concurrent benchmarking and comparative analysis of rate limiting algorithms under load.
 */
@RestController
@RequestMapping({"/api/benchmark", "/api/v1/benchmark"})
@RequiredArgsConstructor
@Slf4j
public class BenchmarkController {

    private final RateLimiterService rateLimiterService;
    private final MemoryAnalysisService memoryAnalysisService;
    private final FixedWindowService fixedWindowService;
    private final AdaptiveRateLimiter adaptiveRateLimiter;
    private final CompleteBenchmarkService completeBenchmarkService;
    private final AlgorithmBenchmarkRepository benchmarkRepository;
    private final RateLimitRules rateLimitRules;

    /**
     * Runs a concurrent benchmark of the Token Bucket rate limiter.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBenchmark(
            @RequestParam(defaultValue = "10") int threads,
            @RequestParam(defaultValue = "100") int requestsPerThread,
            @RequestParam(defaultValue = "api-global") String endpoint) {
        return runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.TOKEN_BUCKET);
    }

    /**
     * Runs a concurrent benchmark of the Sliding Window rate limiter.
     */
    @PostMapping("/sliding-window")
    public ResponseEntity<Map<String, Object>> runSlidingWindowBenchmark(
            @RequestParam(defaultValue = "10") int threads,
            @RequestParam(defaultValue = "100") int requestsPerThread,
            @RequestParam(defaultValue = "api-global") String endpoint) {
        return runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.SLIDING_WINDOW);
    }

    /**
     * Runs a concurrent benchmark of the Fixed Window rate limiter.
     */
    @PostMapping("/fixed-window")
    public ResponseEntity<Map<String, Object>> runFixedWindowBenchmark(
            @RequestParam(defaultValue = "10") int threads,
            @RequestParam(defaultValue = "100") int requestsPerThread,
            @RequestParam(defaultValue = "api-global") String endpoint) {
        return runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.FIXED_WINDOW);
    }

    /**
     * Runs a complete multi-algorithm comparison asynchronously.
     */
    @PostMapping("/full")
    @Async
    public CompletableFuture<ResponseEntity<CompleteBenchmarkService.FullBenchmarkResult>> runFullBenchmark(
            @RequestParam(defaultValue = "10") int threads,
            @RequestParam(defaultValue = "100") int requestsPerThread,
            @RequestParam(defaultValue = "api-global") String endpoint) {
        int totalRequests = threads * requestsPerThread;
        String identifier = "bench-full-" + UUID.randomUUID().toString().substring(0, 8);
        CompleteBenchmarkService.FullBenchmarkResult result =
                completeBenchmarkService.runFullBenchmark(identifier, endpoint, totalRequests);
        return CompletableFuture.completedFuture(ResponseEntity.ok(result));
    }

    /**
     * Queries historical comparative results stored inside algorithm_benchmarks.
     */
    @GetMapping("/results")
    public ResponseEntity<List<com.scalekit.analytics.domain.AlgorithmBenchmark>> getResults() {
        return ResponseEntity.ok(benchmarkRepository.findAll());
    }

    /**
     * Serves details of the latest benchmark run.
     */
    @GetMapping("/latest")
    public ResponseEntity<List<com.scalekit.analytics.domain.AlgorithmBenchmark>> getLatest() {
        List<com.scalekit.analytics.domain.AlgorithmBenchmark> all = benchmarkRepository.findAll();
        all.sort((a, b) -> b.getTestedAt().compareTo(a.getTestedAt()));
        return ResponseEntity.ok(all.stream().limit(10).collect(Collectors.toList()));
    }

    /**
     * Submits rate checks under mock CPU/health load conditions.
     */
    @PostMapping("/adaptive")
    public ResponseEntity<AdaptiveResult> runAdaptiveCheck(
            @RequestParam String identifier,
            @RequestParam String endpoint,
            @RequestParam(required = false) Double mockHealthFactor) {

        if (mockHealthFactor != null) {
            adaptiveRateLimiter.setHealthFactorForTesting(mockHealthFactor);
        } else {
            adaptiveRateLimiter.clearTestOverride();
        }

        try {
            RateLimitRules.EndpointRule rule = rateLimitRules.getEndpoints() != null ?
                    rateLimitRules.getEndpoints().get(endpoint) : null;
            if (rule == null) {
                rule = rateLimitRules.getEndpoints() != null ?
                        rateLimitRules.getEndpoints().get("api-global") : null;
            }
            int baseLimit = rule != null ? rule.getRequestsPerMinute() : 100;
            String key = "ad:" + endpoint + ":" + identifier;
            AdaptiveResult result = adaptiveRateLimiter.tryConsume(key, baseLimit, 60);
            return ResponseEntity.ok(result);
        } finally {
            if (mockHealthFactor != null) {
                adaptiveRateLimiter.clearTestOverride();
            }
        }
    }

    /**
     * Returns system health DTO metrics.
     */
    @GetMapping("/system-health")
    public ResponseEntity<SystemHealthDto> getSystemHealth() {
        return ResponseEntity.ok(adaptiveRateLimiter.getSystemHealth());
    }

    /**
     * Executes a concurrent load test workload for all algorithms and compares latency, memory, and decisions.
     */
    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareAlgorithms(
            @RequestParam(defaultValue = "10") int threads,
            @RequestParam(defaultValue = "100") int requestsPerThread,
            @RequestParam(defaultValue = "api-global") String endpoint) {

        log.info("Starting comparative benchmark: threads={}, requestsPerThread={}, endpoint={}",
                threads, requestsPerThread, endpoint);

        Map<String, Object> swResult = runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.SLIDING_WINDOW).getBody();
        Map<String, Object> tbResult = runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.TOKEN_BUCKET).getBody();
        Map<String, Object> fwResult = runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.FIXED_WINDOW).getBody();
        Map<String, Object> adResult = runBenchmarkForAlgorithm(threads, requestsPerThread, endpoint, RateLimitAlgorithm.ADAPTIVE).getBody();

        Map<String, Object> comparison = new LinkedHashMap<>();
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("SLIDING_WINDOW", swResult);
        results.put("TOKEN_BUCKET", tbResult);
        results.put("FIXED_WINDOW", fwResult);
        results.put("ADAPTIVE", adResult);

        comparison.put("results", results);

        // Analyze latency and choose winner
        double p99Sw = getP99Ms(swResult);
        double p99Tb = getP99Ms(tbResult);
        double p99Fw = getP99Ms(fwResult);
        double p99Ad = getP99Ms(adResult);

        String winner = "TOKEN_BUCKET";
        double minP99 = p99Tb;
        if (p99Sw < minP99) { minP99 = p99Sw; winner = "SLIDING_WINDOW"; }
        if (p99Fw < minP99) { minP99 = p99Fw; winner = "FIXED_WINDOW"; }
        if (p99Ad < minP99) { minP99 = p99Ad; winner = "ADAPTIVE"; }

        comparison.put("winner", winner);
        comparison.put("recommendation",
                "TOKEN_BUCKET is generally recommended for highly scaled public endpoints due to its burst capacity and O(1) space complexity. " +
                "SLIDING_WINDOW is recommended for strict low-volume operations. " +
                "FIXED_WINDOW is recommended for lowest memory and high speed. " +
                "ADAPTIVE auto-protects the system under high load.");

        // Fetch memory comparisons
        comparison.put("memoryAnalysis", memoryAnalysisService.analyzeMemoryUsage());

        return ResponseEntity.ok(comparison);
    }

    private double getP99Ms(Map<String, Object> result) {
        if (result != null && result.get("latency") != null) {
            Map<?, ?> lat = (Map<?, ?>) result.get("latency");
            if (lat.get("p99Ms") != null) {
                return ((Number) lat.get("p99Ms")).doubleValue();
            }
        }
        return Double.MAX_VALUE;
    }


    private ResponseEntity<Map<String, Object>> runBenchmarkForAlgorithm(
            int threads, int requestsPerThread, String endpoint, RateLimitAlgorithm algorithm) {

        threads = Math.min(threads, 200);
        requestsPerThread = Math.min(requestsPerThread, 10_000);

        int totalRequests = threads * requestsPerThread;
        log.info("Running benchmark for algorithm={}: threads={}, requestsPerThread={}, total={}, endpoint={}",
                algorithm, threads, requestsPerThread, totalRequests, endpoint);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        int finalRequestsPerThread = requestsPerThread;
        int finalThreads = threads;
        for (int t = 0; t < finalThreads; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await(); // synchronized start
                    for (int r = 0; r < finalRequestsPerThread; r++) {
                        String identifier = "bench-thread-" + threadId + "-" + algorithm;
                        long start = System.nanoTime();
                        RateLimitResponse response = rateLimiterService.isAllowed(identifier, endpoint, algorithm);
                        long elapsed = System.nanoTime() - start;
                        latencies.add(elapsed);
                        if (response.isAllowed()) {
                            allowedCount.incrementAndGet();
                        } else {
                            rejectedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long benchStart = System.nanoTime();
        startLatch.countDown(); // fire!
        try {
            doneLatch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long benchElapsed = System.nanoTime() - benchStart;
        executor.shutdown();

        List<Long> sorted = latencies.stream().sorted().collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", algorithm.name());
        result.put("threads", finalThreads);
        result.put("requestsPerThread", finalRequestsPerThread);
        result.put("totalRequests", totalRequests);
        result.put("allowed", allowedCount.get());
        result.put("rejected", rejectedCount.get());
        result.put("totalDurationMs", benchElapsed / 1_000_000.0);
        result.put("throughputRps", totalRequests / (benchElapsed / 1_000_000_000.0));

        if (!sorted.isEmpty()) {
            result.put("latency", Map.of(
                    "minNs", sorted.get(0),
                    "maxNs", sorted.get(sorted.size() - 1),
                    "p50Ns", percentile(sorted, 50),
                    "p95Ns", percentile(sorted, 95),
                    "p99Ns", percentile(sorted, 99),
                    "avgNs", sorted.stream().mapToLong(Long::longValue).average().orElse(0),
                    "p50Ms", percentile(sorted, 50) / 1_000_000.0,
                    "p95Ms", percentile(sorted, 95) / 1_000_000.0,
                    "p99Ms", percentile(sorted, 99) / 1_000_000.0
            ));
        }

        return ResponseEntity.ok(result);
    }

    private long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
