package com.scalekit.cache.controller;

import com.scalekit.cache.dto.StrategyBenchmarkResult;
import com.scalekit.cache.dto.StrategyMetrics;
import com.scalekit.cache.service.CacheStrategyService;
import com.scalekit.cache.strategy.CacheStrategyType;
import com.scalekit.cache.strategy.StampedePreventionService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing cache‑strategy operations and demo endpoints.
 */
@RestController
@RequestMapping("/api/v1/cache/strategies")
@Slf4j
public class CacheStrategyController {

    private final CacheStrategyService cacheStrategyService;
    private final StampedePreventionService stampedeService;

    public CacheStrategyController(CacheStrategyService cacheStrategyService,
                                   StampedePreventionService stampedeService) {
        this.cacheStrategyService = cacheStrategyService;
        this.stampedeService = stampedeService;
    }

    @GetMapping
    public ResponseEntity<List<String>> listStrategies() {
        List<String> names = List.of(
                CacheStrategyType.WRITE_THROUGH.name(),
                CacheStrategyType.WRITE_BEHIND.name(),
                CacheStrategyType.CACHE_ASIDE.name(),
                CacheStrategyType.READ_THROUGH.name(),
                CacheStrategyType.REFRESH_AHEAD.name()
        );
        return ResponseEntity.ok(names);
    }

    @PostMapping("/demo")
    public ResponseEntity<String> demo(@RequestBody DemoRequest request) {
        String result;
        switch (request.getOperation().toUpperCase()) {
            case "GET":
                result = cacheStrategyService.get(request.getKey(), request.getStrategy());
                break;
            case "PUT":
                cacheStrategyService.put(request.getKey(), request.getValue(), request.getStrategy());
                result = "OK";
                break;
            case "DELETE":
                cacheStrategyService.delete(request.getKey(), request.getStrategy());
                result = "OK";
                break;
            default:
                return ResponseEntity.badRequest().body("Unsupported operation");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/benchmark")
    public ResponseEntity<StrategyBenchmarkResult> benchmark(@RequestBody BenchmarkRequest req) {
        // Placeholder – real benchmark not yet implemented
        throw new UnsupportedOperationException("Benchmark not implemented yet");
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<CacheStrategyType, com.scalekit.cache.dto.CacheStrategyStats>> allStats() {
        return ResponseEntity.ok(cacheStrategyService.getAllStats());
    }

    @PostMapping("/stampede-demo")
    public ResponseEntity<String> stampedeDemo(@RequestBody StampedeDemoRequest req) {
        // Simplified demo – just invoke the chosen prevention method once
        // In a real demo we would fire many concurrent threads.
        String result = "Stampede demo not implemented";
        return ResponseEntity.ok(result);
    }

    // ---------- Request DTOs ----------
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemoRequest {
        private CacheStrategyType strategy;
        private String operation; // GET, PUT, DELETE
        private String key;
        private String value; // optional for PUT
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BenchmarkRequest {
        private int operations;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StampedeDemoRequest {
        private int concurrentRequests;
        private String preventionStrategy; // MUTEX, PER, SWR
        private String key;
    }
}
