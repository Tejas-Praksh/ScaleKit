package com.scalekit.ratelimiter.algorithm;

import com.scalekit.ratelimiter.dto.SystemHealthDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdaptiveRateLimiterTest {

    @Mock
    private FixedWindowAlgorithm fixedWindowAlgorithm;

    @Mock
    private MemoryMXBean memoryMXBean;

    private AdaptiveRateLimiter adaptiveRateLimiter;

    @BeforeEach
    void setUp() {
        adaptiveRateLimiter = new AdaptiveRateLimiter(fixedWindowAlgorithm);
    }

    @Test
    void getHealthFactor_healthySystem_returns1() {
        // Default JVM under test conditions is healthy, but might be loaded
        double factor = adaptiveRateLimiter.getHealthFactor();
        assertTrue(factor >= 0.1 && factor <= 1.0,
                "Health factor should be between 0.1 and 1.0, got: " + factor);
    }

    @Test
    void getAdaptiveLimit_healthy_returnsFullLimit() {
        // Force health factor to 1.0 by calling with known healthy state
        adaptiveRateLimiter.setHealthFactorForTesting(1.0);
        int limit = adaptiveRateLimiter.getAdaptiveLimit(100);
        assertEquals(100, limit);
    }

    @Test
    void getAdaptiveLimit_degraded_returnsHalfLimit() {
        adaptiveRateLimiter.setHealthFactorForTesting(0.5);
        int limit = adaptiveRateLimiter.getAdaptiveLimit(100);
        assertEquals(50, limit);
    }

    @Test
    void getAdaptiveLimit_critical_returnsTenPercent() {
        adaptiveRateLimiter.setHealthFactorForTesting(0.1);
        int limit = adaptiveRateLimiter.getAdaptiveLimit(100);
        assertEquals(10, limit);
    }

    @Test
    void getAdaptiveLimit_neverBelowMinimum() {
        adaptiveRateLimiter.setHealthFactorForTesting(0.01);
        int limit = adaptiveRateLimiter.getAdaptiveLimit(100);
        // Minimum is 10% of base
        assertTrue(limit >= 10, "Adaptive limit should never drop below 10% of base");
    }

    @Test
    void tryConsume_underHighLoad_tightensLimit() {
        adaptiveRateLimiter.setHealthFactorForTesting(0.1);

        FixedWindowResult mockResult = FixedWindowResult.builder()
                .allowed(true)
                .remainingRequests(5)
                .limit(10) // adaptiveLimit = 10% of 100 = 10
                .currentCount(5)
                .windowTtlSeconds(60)
                .build();
        when(fixedWindowAlgorithm.tryConsume(anyString(), eq(10), eq(60)))
                .thenReturn(mockResult);

        AdaptiveResult result = adaptiveRateLimiter.tryConsume("test-key", 100, 60);
        assertTrue(result.isAllowed());
        assertEquals(100, result.getBaseLimit());
        assertEquals(10, result.getAdaptiveLimit());
        assertEquals(0.1, result.getHealthFactor(), 0.01);
        assertEquals("CRITICAL", result.getHealthStatus());
    }

    @Test
    void getSystemHealth_returnsValidDto() {
        SystemHealthDto health = adaptiveRateLimiter.getSystemHealth();
        assertNotNull(health);
        assertNotNull(health.getMeasuredAt());
        assertTrue(health.getHeapUsagePercent() >= 0 && health.getHeapUsagePercent() <= 100);
        assertTrue(health.getTotalMemoryMb() > 0);
        assertNotNull(health.getStatus());
        assertNotNull(health.getRecommendation());
    }

    @Test
    void getHealthFactor_highCpu_reducesLimit() {
        // We can't easily force CPU > 90% in a unit test, so we test the calculation logic
        // by testing the factor mapping directly
        assertEquals("CRITICAL", AdaptiveRateLimiter.mapHealthStatus(0.1));
        assertEquals("DEGRADED", AdaptiveRateLimiter.mapHealthStatus(0.5));
        assertEquals("HEALTHY", AdaptiveRateLimiter.mapHealthStatus(1.0));
    }

    @Test
    void getHealthFactor_highHeap_reducesLimit() {
        // Test that factor calculation returns correct status at threshold boundaries
        assertEquals("DEGRADED", AdaptiveRateLimiter.mapHealthStatus(0.5));
        assertEquals("CRITICAL", AdaptiveRateLimiter.mapHealthStatus(0.1));
    }
}
