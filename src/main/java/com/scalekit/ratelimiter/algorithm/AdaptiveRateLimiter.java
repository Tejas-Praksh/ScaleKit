package com.scalekit.ratelimiter.algorithm;

import com.scalekit.ratelimiter.dto.SystemHealthDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Instant;

/**
 * Adaptive rate limiter that auto-adjusts limits based on server health metrics.
 *
 * <p>Wraps the {@link FixedWindowAlgorithm} and dynamically calculates an effective
 * limit based on JVM CPU load and heap utilization. The health factor is a value
 * between 0.1 and 1.0:</p>
 *
 * <ul>
 *   <li>1.0 = HEALTHY — full limit applied</li>
 *   <li>0.5 = DEGRADED — 50% of limit</li>
 *   <li>0.1 = CRITICAL — 10% of limit (minimum floor)</li>
 * </ul>
 *
 * <p>The system is self-healing: when load subsides, limits automatically relax.</p>
 */
@Component
@Slf4j
public class AdaptiveRateLimiter {

    private final FixedWindowAlgorithm fixedWindowAlgorithm;
    private final com.sun.management.OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    /**
     * Override for testing. Set to non-null to bypass real metric collection.
     */
    private Double testHealthFactor = null;

    private static final double MIN_HEALTH_FACTOR = 0.1;  // Never go below 10%
    private static final double CPU_HIGH_THRESHOLD = 0.8;  // 80% CPU
    private static final double HEAP_HIGH_THRESHOLD = 0.85; // 85% heap

    public AdaptiveRateLimiter(FixedWindowAlgorithm fixedWindowAlgorithm) {
        this.fixedWindowAlgorithm = fixedWindowAlgorithm;
        this.osMxBean = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    /**
     * The main entry point. Tries to consume a request against an adaptively computed limit.
     *
     * @param key           the rate limiter key (e.g., user IP or API key)
     * @param baseLimit     the configured limit under healthy conditions
     * @param windowSeconds the window duration in seconds
     * @return {@link AdaptiveResult} with decision + health metadata
     */
    public AdaptiveResult tryConsume(String key, int baseLimit, int windowSeconds) {
        double factor = getHealthFactor();
        int adaptiveLimit = getAdaptiveLimit(baseLimit, factor);

        FixedWindowResult fwResult = fixedWindowAlgorithm.tryConsume(key, adaptiveLimit, windowSeconds);

        String status = mapHealthStatus(factor);

        if (!"HEALTHY".equals(status)) {
            log.warn("Adaptive rate limiting active: status={} factor={} adaptiveLimit={}/{}",
                    status, factor, adaptiveLimit, baseLimit);
        }

        return AdaptiveResult.builder()
                .allowed(fwResult.isAllowed())
                .baseLimit(baseLimit)
                .adaptiveLimit(adaptiveLimit)
                .healthFactor(factor)
                .healthStatus(status)
                .currentCount(fwResult.getCurrentCount())
                .remainingRequests(fwResult.getRemainingRequests())
                .windowTtlSeconds(fwResult.getWindowTtlSeconds())
                .executionTimeNanos(fwResult.getExecutionTimeNanos())
                .build();
    }

    /**
     * Calculates the server health factor in the range [0.1, 1.0].
     *
     * <p>Formula:
     * <pre>
     *   cpuFactor  = max(0, 1.0 - cpuLoad)
     *   heapFactor = max(0, 1.0 - heapUsage)
     *   factor     = max(0.1, min(cpuFactor, heapFactor))
     * </pre>
     */
    public double getHealthFactor() {
        if (testHealthFactor != null) {
            return testHealthFactor;
        }

        try {
            double cpuLoad = osMxBean.getCpuLoad();
            if (cpuLoad < 0) cpuLoad = 0; // JVM may report -1 if unavailable

            MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
            double heapPercent = (double) heapUsage.getUsed() / heapUsage.getMax();

            double cpuFactor = Math.max(0, 1.0 - cpuLoad);
            double heapFactor = Math.max(0, 1.0 - heapPercent);

            double factor = Math.min(cpuFactor, heapFactor);
            return Math.max(MIN_HEALTH_FACTOR, factor);
        } catch (Exception e) {
            log.warn("Failed to compute health factor, defaulting to 1.0: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * Computes the adaptive limit given a health factor.
     */
    public int getAdaptiveLimit(int baseLimit) {
        return getAdaptiveLimit(baseLimit, getHealthFactor());
    }

    /**
     * Computes the adaptive limit with an explicit factor.
     * Never drops below 10% of baseLimit.
     */
    int getAdaptiveLimit(int baseLimit, double factor) {
        int adaptive = (int) Math.round(baseLimit * factor);
        int floor = (int) Math.ceil(baseLimit * MIN_HEALTH_FACTOR);
        return Math.max(adaptive, floor);
    }

    /**
     * Returns a snapshot of the current system health metrics.
     */
    public SystemHealthDto getSystemHealth() {
        double cpuLoad = 0;
        try {
            cpuLoad = osMxBean.getCpuLoad();
            if (cpuLoad < 0) cpuLoad = 0;
        } catch (Exception e) {
            log.debug("Could not read CPU load: {}", e.getMessage());
        }

        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        double heapPercent = (double) heapUsage.getUsed() / heapUsage.getMax();

        double factor = getHealthFactor();
        String status = mapHealthStatus(factor);

        return SystemHealthDto.builder()
                .cpuUsagePercent(Math.round(cpuLoad * 100.0 * 100.0) / 100.0)
                .heapUsagePercent(Math.round(heapPercent * 100.0 * 100.0) / 100.0)
                .availableProcessors(Runtime.getRuntime().availableProcessors())
                .totalMemoryMb(heapUsage.getMax() / (1024 * 1024))
                .usedMemoryMb(heapUsage.getUsed() / (1024 * 1024))
                .freeMemoryMb((heapUsage.getMax() - heapUsage.getUsed()) / (1024 * 1024))
                .healthFactor(factor)
                .status(status)
                .recommendation(mapRecommendation(status))
                .measuredAt(Instant.now())
                .build();
    }


    /**
     * Maps a health factor to a human-readable status string.
     */
    public static String mapHealthStatus(double factor) {
        if (factor >= 0.7) return "HEALTHY";
        if (factor >= 0.3) return "DEGRADED";
        return "CRITICAL";
    }

    private String mapRecommendation(String status) {
        return switch (status) {
            case "HEALTHY" -> "System is healthy. Full rate limits applied.";
            case "DEGRADED" -> "System under moderate load. Rate limits tightened. Monitor closely.";
            case "CRITICAL" -> "System under heavy load. Rate limits severely restricted. Scale up recommended.";
            default -> "Unknown status.";
        };
    }

    /**
     * Test-only hook to override the health factor for deterministic unit testing.
     */
    public void setHealthFactorForTesting(double factor) {
        this.testHealthFactor = factor;
    }

    /**
     * Clears the test override, reverting to real metric collection.
     */
    public void clearTestOverride() {
        this.testHealthFactor = null;
    }
}
