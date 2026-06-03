package com.scalekit.common.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerRegistryTest {

    private CircuitBreakerRegistry registry;
    private final String route = "test-route";

    @BeforeEach
    void setUp() {
        registry = new CircuitBreakerRegistry();
    }

    @Test
    void allowRequest_closed_allows() {
        assertTrue(registry.allowRequest(route));
        CircuitBreakerStats stats = registry.getStats(route);
        assertEquals("CLOSED", stats.getState());
        assertEquals(0, stats.getFailures());
        assertEquals(0, stats.getSuccesses());
    }

    @Test
    void recordFailure_threshold_opens() {
        // Threshold is 5 failures
        for (int i = 0; i < 5; i++) {
            assertTrue(registry.allowRequest(route));
            registry.recordFailure(route);
        }

        // 6th request should be blocked
        assertFalse(registry.allowRequest(route));
        CircuitBreakerStats stats = registry.getStats(route);
        assertEquals("OPEN", stats.getState());
        assertEquals(5, stats.getFailures());
    }

    @Test
    void resetTime_open_movesToHalfOpen() {
        // Open the circuit
        for (int i = 0; i < 5; i++) {
            registry.recordFailure(route);
        }
        assertFalse(registry.allowRequest(route));

        // Manually adjust openedAt back by 11 seconds to trigger reset check
        registry.forceOpenTimeForTesting(route, Instant.now().minusSeconds(11));

        // Should allow next request (transitions to HALF_OPEN)
        assertTrue(registry.allowRequest(route));
        CircuitBreakerStats stats = registry.getStats(route);
        assertEquals("HALF_OPEN", stats.getState());
    }

    @Test
    void allowRequest_halfOpen_allowsLimited() {
        // Move to HALF_OPEN
        for (int i = 0; i < 5; i++) {
            registry.recordFailure(route);
        }
        registry.forceOpenTimeForTesting(route, Instant.now().minusSeconds(11));
        
        // Trigger half-open and check limit of 3
        assertTrue(registry.allowRequest(route)); // 1
        assertTrue(registry.allowRequest(route)); // 2
        assertTrue(registry.allowRequest(route)); // 3
        
        // 4th request should be rejected (half-open limit reached)
        assertFalse(registry.allowRequest(route));
    }

    @Test
    void recordSuccess_halfOpen_closes() {
        // Move to HALF_OPEN
        for (int i = 0; i < 5; i++) {
            registry.recordFailure(route);
        }
        registry.forceOpenTimeForTesting(route, Instant.now().minusSeconds(11));

        assertTrue(registry.allowRequest(route));
        
        // Record 3 successes
        registry.recordSuccess(route);
        registry.recordSuccess(route);
        registry.recordSuccess(route);

        // Circuit should close again and reset counters
        assertTrue(registry.allowRequest(route));
        CircuitBreakerStats stats = registry.getStats(route);
        assertEquals("CLOSED", stats.getState());
        assertEquals(0, stats.getFailures());
        assertEquals(0, stats.getSuccesses());
    }
}
