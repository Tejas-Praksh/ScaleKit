package com.scalekit.common.observability;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class MetricsCollector {

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    // Sliding window queues for QPS and Error Rate
    private final ConcurrentLinkedQueue<Long> requestTimestamps = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RequestOutcome> outcomeWindow = new ConcurrentLinkedQueue<>();

    // Cache hit/miss tracker
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    private static class RequestOutcome {
        final long timestamp;
        final boolean isError;

        RequestOutcome(long timestamp, boolean isError) {
            this.timestamp = timestamp;
            this.isError = isError;
        }
    }

    public void recordRequest(String endpoint, String method, int status, long durationMs) {
        long now = System.currentTimeMillis();

        // Micrometer Metrics
        String counterKey = "gateway.requests." + endpoint + "." + method + "." + status;
        counters.computeIfAbsent(counterKey, k -> Counter.builder("scalekit.gateway.requests")
                .tag("endpoint", endpoint)
                .tag("method", method)
                .tag("status", String.valueOf(status))
                .register(meterRegistry)).increment();

        String timerKey = "gateway.duration." + endpoint;
        timers.computeIfAbsent(timerKey, k -> Timer.builder("scalekit.gateway.request.duration")
                .tag("endpoint", endpoint)
                .tag("method", method)
                .tag("status", String.valueOf(status))
                .publishPercentileHistogram()
                .register(meterRegistry)).record(durationMs, TimeUnit.MILLISECONDS);

        // Sliding Window computations
        requestTimestamps.add(now);
        outcomeWindow.add(new RequestOutcome(now, status >= 400));

        // Perform lazy trimming
        trimWindows(now);
    }

    private void trimWindows(long now) {
        // Trim requests older than 1 second (1000ms)
        while (!requestTimestamps.isEmpty() && requestTimestamps.peek() < now - 1000) {
            requestTimestamps.poll();
        }

        // Trim outcomes older than 60 seconds (60000ms)
        while (!outcomeWindow.isEmpty() && outcomeWindow.peek().timestamp < now - 60000) {
            outcomeWindow.poll();
        }
    }

    public void recordCacheOperation(String cacheName, String operation, boolean hit) {
        String counterKey = "cache." + cacheName + "." + operation + "." + (hit ? "hit" : "miss");
        counters.computeIfAbsent(counterKey, k -> Counter.builder("scalekit.cache.operation")
                .tag("cacheName", cacheName)
                .tag("operation", operation)
                .tag("hit", String.valueOf(hit))
                .register(meterRegistry)).increment();

        if (hit) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }

        // Dynamic Gauge registration for hit rate if not already done
        String gaugeKey = "cache.hitrate." + cacheName;
        gauges.computeIfAbsent(gaugeKey, k -> Gauge.builder("scalekit.cache.hitrate", () -> {
            long hits = cacheHits.get();
            long total = hits + cacheMisses.get();
            return total == 0 ? 0.0 : (double) hits / total;
        }).tag("cacheName", cacheName).register(meterRegistry));
    }

    public void recordRateLimitCheck(String endpoint, boolean allowed) {
        String counterKey = "ratelimit." + endpoint + "." + (allowed ? "allowed" : "rejected");
        counters.computeIfAbsent(counterKey, k -> Counter.builder("scalekit.ratelimit.check")
                .tag("endpoint", endpoint)
                .tag("allowed", String.valueOf(allowed))
                .register(meterRegistry)).increment();
    }

    public void recordUrlOperation(String operation, boolean success) {
        String counterKey = "url." + operation + "." + (success ? "success" : "failure");
        counters.computeIfAbsent(counterKey, k -> Counter.builder("scalekit.url.operation")
                .tag("operation", operation)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)).increment();
    }

    public void recordAlgorithmExecution(String algorithm, String operation, long durationNs) {
        String timerKey = "algorithm." + algorithm + "." + operation;
        timers.computeIfAbsent(timerKey, k -> Timer.builder("scalekit.algorithm.execution")
                .tag("algorithm", algorithm)
                .tag("operation", operation)
                .register(meterRegistry)).record(durationNs, TimeUnit.NANOSECONDS);
    }

    public double getCurrentQPS() {
        trimWindows(System.currentTimeMillis());
        return requestTimestamps.size();
    }

    public double getErrorRate() {
        long now = System.currentTimeMillis();
        trimWindows(now);
        long total = outcomeWindow.size();
        if (total == 0) return 0.0;
        long errors = outcomeWindow.stream().filter(o -> o.isError).count();
        return (double) errors / total;
    }

    public double getP99Latency(String endpoint) {
        // Query timer in micrometer registry
        try {
            Timer timer = meterRegistry.find("scalekit.gateway.request.duration")
                    .tag("endpoint", endpoint)
                    .timer();
            if (timer != null && timer.takeSnapshot() != null) {
                for (var val : timer.takeSnapshot().percentileValues()) {
                    if (val.percentile() == 0.99) {
                        return val.value(TimeUnit.MILLISECONDS);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve P99 latency metric: {}", e.getMessage());
        }
        return 0.0;
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }
}
