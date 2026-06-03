package com.scalekit.ratelimiter.controller;

import com.scalekit.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketAlgorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller providing real-time diagnostic and state data for rate-limiter visualizations.
 */
@RestController
@RequestMapping("/api/v1/rate-limiter/visualization")
@RequiredArgsConstructor
public class RateLimitVisualizationController {

    private final TokenBucketAlgorithm tokenBucketAlgorithm;
    private final SlidingWindowAlgorithm slidingWindowAlgorithm;

    @GetMapping("/token-bucket/{key}")
    public ResponseEntity<Map<String, Object>> getTokenBucketState(@PathVariable String key) {
        Optional<TokenBucketAlgorithm.BucketState> statsOpt = tokenBucketAlgorithm.getBucketStats(key);

        Map<String, Object> state = new HashMap<>();
        if (statsOpt.isPresent()) {
            TokenBucketAlgorithm.BucketState stats = statsOpt.get();
            state.put("currentTokens", stats.getTokens());
            state.put("lastRefillTime", stats.getLastRefillTimestamp());
            state.put("totalRequests", stats.getTotalRequests());
            state.put("totalRejected", stats.getTotalRejected());
        } else {
            state.put("currentTokens", 0.0);
            state.put("lastRefillTime", 0L);
            state.put("totalRequests", 0L);
            state.put("totalRejected", 0L);
        }

        // Add placeholders for frontend visualizer configuration
        state.put("capacity", 10.0);
        state.put("refillRate", 1.0);
        state.put("history", List.of()); // last 60 seconds details can be appended or simulated in UI

        return ResponseEntity.ok(state);
    }

    @GetMapping("/sliding-window/{key}")
    public ResponseEntity<Map<String, Object>> getSlidingWindowState(@PathVariable String key) {
        long windowSizeMs = 60000L;
        List<Long> timestamps = slidingWindowAlgorithm.getRequestTimestamps(key, windowSizeMs);
        long count = slidingWindowAlgorithm.getRequestCount(key, windowSizeMs);
        long now = System.currentTimeMillis();

        Map<String, Object> state = new HashMap<>();
        state.put("requestTimestamps", timestamps);
        state.put("windowSizeMs", windowSizeMs);
        state.put("limit", 10); // default reference limit
        state.put("currentCount", count);
        state.put("windowStart", now - windowSizeMs);
        state.put("windowEnd", now);

        return ResponseEntity.ok(state);
    }
}
