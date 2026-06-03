package com.scalekit.cache.controller;

import com.scalekit.cache.algorithm.BloomFilter;
import com.scalekit.cache.dto.*;
import com.scalekit.cache.service.UrlDuplicateDetector;
import com.scalekit.common.dto.ApiResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller exposing Bloom Filter operations, URL deduplication,
 * false‑positive demos, and benchmarks.
 */
@RestController
@RequestMapping("/api/v1/bloom-filter")
@Slf4j
public class BloomFilterController {

    private final UrlDuplicateDetector urlDuplicateDetector;
    private final Map<String, BloomFilter<String>> namedFilters = new ConcurrentHashMap<>();

    public BloomFilterController(UrlDuplicateDetector urlDuplicateDetector) {
        this.urlDuplicateDetector = urlDuplicateDetector;
    }

    // ── Named Filter CRUD ────────────────────────────────────────────────

    @PostMapping("/filters")
    public ResponseEntity<ApiResponse<BloomFilterStats>> createFilter(@RequestBody CreateFilterRequest req) {
        long start = System.nanoTime();
        BloomFilter<String> filter = new BloomFilter<>(req.getExpectedInsertions(), req.getFalsePositiveRate());
        namedFilters.put(req.getName(), filter);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return ResponseEntity.ok(ApiResponse.success(filter.getStats(),
                "Filter '" + req.getName() + "' created", elapsed));
    }

    @PostMapping("/filters/{name}/add")
    public ResponseEntity<ApiResponse<String>> addItem(@PathVariable String name, @RequestBody ItemRequest req) {
        BloomFilter<String> filter = namedFilters.get(name);
        if (filter == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Filter not found: " + name, "NOT_FOUND"));
        }
        filter.add(req.getItem());
        return ResponseEntity.ok(ApiResponse.success("Added", "Item added to filter '" + name + "'"));
    }

    @PostMapping("/filters/{name}/check")
    public ResponseEntity<ApiResponse<BloomCheckResult>> checkItem(@PathVariable String name,
                                                                    @RequestBody ItemRequest req) {
        BloomFilter<String> filter = namedFilters.get(name);
        if (filter == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Filter not found: " + name, "NOT_FOUND"));
        }
        boolean might = filter.mightContain(req.getItem());
        BloomCheckResult result = BloomCheckResult.builder()
                .item(req.getItem())
                .mightExist(might)
                .definitelyNotExist(!might)
                .explanation(might
                        ? "Item MIGHT exist in the set (possible false positive)"
                        : "Item DEFINITELY does NOT exist in the set (100% accurate)")
                .falsePositiveProbability(filter.getEstimatedCurrentFPR())
                .build();
        return ResponseEntity.ok(ApiResponse.success(result, "Check complete"));
    }

    @GetMapping("/filters/{name}/stats")
    public ResponseEntity<ApiResponse<BloomFilterStats>> getStats(@PathVariable String name) {
        BloomFilter<String> filter = namedFilters.get(name);
        if (filter == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Filter not found: " + name, "NOT_FOUND"));
        }
        return ResponseEntity.ok(ApiResponse.success(filter.getStats(), "Stats for filter '" + name + "'"));
    }

    // ── False‑Positive Demo ──────────────────────────────────────────────

    @PostMapping("/demo")
    public ResponseEntity<ApiResponse<BloomFilterDemo>> demo(@RequestBody DemoRequest req) {
        long start = System.nanoTime();
        int insertions = req.getInsertions() > 0 ? req.getInsertions() : 10_000;
        int checks = req.getChecks() > 0 ? req.getChecks() : 10_000;
        double fpr = 0.001;

        BloomFilter<String> filter = new BloomFilter<>(insertions, fpr);

        // Insert items
        Set<String> insertedSet = new HashSet<>();
        for (int i = 0; i < insertions; i++) {
            String item = "item-" + i;
            filter.add(item);
            insertedSet.add(item);
        }

        // Check items that were NOT inserted
        int trueNegatives = 0;
        int falsePositives = 0;
        for (int i = 0; i < checks; i++) {
            String item = "check-not-inserted-" + i;
            if (filter.mightContain(item)) {
                falsePositives++;
            } else {
                trueNegatives++;
            }
        }

        // Check items that WERE inserted (should all return true)
        int truePositives = 0;
        int falseNegatives = 0;
        int verifyCount = Math.min(insertions, checks);
        for (int i = 0; i < verifyCount; i++) {
            String item = "item-" + i;
            if (filter.mightContain(item)) {
                truePositives++;
            } else {
                falseNegatives++;
            }
        }

        double actualFPR = checks == 0 ? 0 : (double) falsePositives / checks;
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        BloomFilterDemo result = BloomFilterDemo.builder()
                .totalInserted(insertions)
                .totalChecked(checks + verifyCount)
                .truePositives(truePositives)
                .trueNegatives(trueNegatives)
                .falsePositives(falsePositives)
                .falseNegatives(falseNegatives)
                .actualFPR(actualFPR)
                .expectedFPR(fpr)
                .analysis(String.format(
                        "Inserted %d items. Checked %d non-members: %d false positives (%.4f%% FPR). "
                                + "Verified %d members: %d false negatives (should be 0). "
                                + "Expected FPR: %.4f%%. %s",
                        insertions, checks, falsePositives, actualFPR * 100,
                        verifyCount, falseNegatives, fpr * 100,
                        falseNegatives == 0 ? "✅ Zero false negatives confirmed!"
                                : "❌ FALSE NEGATIVES DETECTED — this should never happen!"))
                .build();

        return ResponseEntity.ok(ApiResponse.success(result, "Demo complete", elapsed));
    }

    // ── URL Deduplication ────────────────────────────────────────────────

    @PostMapping("/url-dedup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> urlDedup(@RequestBody UrlDedupRequest req) {
        List<String> duplicates = new ArrayList<>();
        List<String> newUrls = new ArrayList<>();

        for (String url : req.getUrls()) {
            if (urlDuplicateDetector.isDuplicate(url)) {
                duplicates.add(url);
            } else {
                urlDuplicateDetector.markAsSeen(url);
                newUrls.add(url);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUrls", req.getUrls().size());
        result.put("newUrls", newUrls.size());
        result.put("duplicates", duplicates.size());
        result.put("duplicateUrls", duplicates);

        return ResponseEntity.ok(ApiResponse.success(result, "URL deduplication complete"));
    }

    @GetMapping("/url-dedup/stats")
    public ResponseEntity<ApiResponse<UrlDuplicateStats>> urlDedupStats() {
        return ResponseEntity.ok(ApiResponse.success(urlDuplicateDetector.getStats(), "URL dedup stats"));
    }

    // ── Benchmark ────────────────────────────────────────────────────────

    @PostMapping("/benchmark")
    public ResponseEntity<ApiResponse<Map<String, Object>>> benchmark(@RequestBody BenchmarkRequest req) {
        int elements = req.getElements() > 0 ? req.getElements() : 100_000;
        int checksPerElement = req.getChecksPerElement() > 0 ? req.getChecksPerElement() : 1;

        // Bloom Filter benchmark
        BloomFilter<String> bf = new BloomFilter<>(elements, 0.001);

        long insertStart = System.nanoTime();
        for (int i = 0; i < elements; i++) {
            bf.add("element-" + i);
        }
        long insertTimeNs = System.nanoTime() - insertStart;

        long lookupStart = System.nanoTime();
        for (int i = 0; i < elements * checksPerElement; i++) {
            bf.mightContain("element-" + (i % elements));
        }
        long lookupTimeNs = System.nanoTime() - lookupStart;

        // HashSet benchmark for comparison
        Set<String> hashSet = new HashSet<>();
        long hsInsertStart = System.nanoTime();
        for (int i = 0; i < elements; i++) {
            hashSet.add("element-" + i);
        }
        long hsInsertTimeNs = System.nanoTime() - hsInsertStart;

        long hsLookupStart = System.nanoTime();
        for (int i = 0; i < elements * checksPerElement; i++) {
            hashSet.contains("element-" + (i % elements));
        }
        long hsLookupTimeNs = System.nanoTime() - hsLookupStart;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elements", elements);
        result.put("checksPerElement", checksPerElement);

        Map<String, Object> bloomResult = new LinkedHashMap<>();
        bloomResult.put("insertThroughput", elements * 1_000_000_000L / Math.max(1, insertTimeNs) + " ops/sec");
        bloomResult.put("lookupThroughput", elements * checksPerElement * 1_000_000_000L / Math.max(1, lookupTimeNs) + " ops/sec");
        bloomResult.put("memoryBits", bf.getBitArraySize());
        bloomResult.put("memoryBytes", bf.getBitArraySize() / 8);
        result.put("bloomFilter", bloomResult);

        Map<String, Object> hashSetResult = new LinkedHashMap<>();
        hashSetResult.put("insertThroughput", elements * 1_000_000_000L / Math.max(1, hsInsertTimeNs) + " ops/sec");
        hashSetResult.put("lookupThroughput", elements * checksPerElement * 1_000_000_000L / Math.max(1, hsLookupTimeNs) + " ops/sec");
        hashSetResult.put("note", "HashSet stores exact elements — much more memory but zero false positives");
        result.put("hashSet", hashSetResult);

        result.put("memorySavings", String.format("Bloom Filter uses ~%.1f%% of HashSet memory",
                (double) (bf.getBitArraySize() / 8) / (elements * 50) * 100)); // rough estimate: 50 bytes/entry for HashSet

        return ResponseEntity.ok(ApiResponse.success(result, "Benchmark complete"));
    }

    // ── Request DTOs ─────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateFilterRequest {
        private String name;
        private int expectedInsertions;
        private double falsePositiveRate;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemRequest {
        private String item;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DemoRequest {
        private int insertions;
        private int checks;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UrlDedupRequest {
        private List<String> urls;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class BenchmarkRequest {
        private int elements;
        private int checksPerElement;
    }
}
