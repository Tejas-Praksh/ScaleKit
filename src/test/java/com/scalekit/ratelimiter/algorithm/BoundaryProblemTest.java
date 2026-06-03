package com.scalekit.ratelimiter.algorithm;

import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the Fixed Window 2x boundary burst problem programmatically.
 *
 * <p>The boundary problem occurs when a client sends the maximum number of requests
 * at the very end of one window, then immediately sends the maximum again at the
 * start of the next window. This results in 2× the limit within a very short timeframe.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class BoundaryProblemTest {

    @Autowired
    private FixedWindowAlgorithm fixedWindowAlgorithm;

    private String key;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
        key = "fw:boundary:" + UUID.randomUUID().toString();
    }

    /**
     * THE most important Fixed Window test.
     *
     * <p>Setup: limit=10, window=2s
     * At the end of window 1: send 10 requests → all allowed
     * Wait for window to expire, then immediately send 10 more → all allowed
     * Total: 20 requests processed despite a limit of 10 per window.
     * This is the boundary burst problem.</p>
     */
    @Test
    void demonstrateBoundaryProblem_proves2xBurst() throws InterruptedException {
        int limit = 10;
        int windowSeconds = 2; // short window for test speed

        // --- End of Window 1: fire 10 requests ---
        int allowedInWindow1 = 0;
        for (int i = 0; i < limit; i++) {
            FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, limit, windowSeconds);
            if (result.isAllowed()) {
                allowedInWindow1++;
            }
        }
        assertEquals(limit, allowedInWindow1,
                "All 10 requests at end of window should be allowed");

        // Verify limit is now exhausted
        assertFalse(fixedWindowAlgorithm.tryConsume(key, limit, windowSeconds).isAllowed(),
                "11th request must be rejected");

        // --- Wait for window to expire ---
        Thread.sleep((windowSeconds * 1000L) + 200); // buffer

        // --- Start of Window 2: fire 10 more requests ---
        int allowedInWindow2 = 0;
        for (int i = 0; i < limit; i++) {
            FixedWindowResult result = fixedWindowAlgorithm.tryConsume(key, limit, windowSeconds);
            if (result.isAllowed()) {
                allowedInWindow2++;
            }
        }
        assertEquals(limit, allowedInWindow2,
                "All 10 requests at start of new window should be allowed");

        // Total: 20 requests in ~2 seconds, but the limit is 10 per 2-second window!
        int total = allowedInWindow1 + allowedInWindow2;
        assertEquals(20, total,
                "Boundary problem: 2x limit (" + total + ") requests were allowed across the window edge");

        double burstMultiplier = (double) total / limit;
        assertEquals(2.0, burstMultiplier, 0.01,
                "Burst multiplier should be exactly 2.0 (the boundary problem)");

        System.out.println("=== BOUNDARY PROBLEM DEMONSTRATED ===");
        System.out.println("Limit: " + limit + " per " + windowSeconds + "s");
        System.out.println("Window 1 (end): " + allowedInWindow1 + " allowed");
        System.out.println("Window 2 (start): " + allowedInWindow2 + " allowed");
        System.out.println("Total in ~" + windowSeconds + "s: " + total + " (2x limit!)");
        System.out.println("Burst multiplier: " + burstMultiplier + "x");
    }

    @Test
    void demonstrateBoundaryProblem_returnsExplanation() {
        BoundaryProblemDemo demo = fixedWindowAlgorithm.demonstrateBoundaryProblem(key, 100);
        assertNotNull(demo);
        assertNotNull(demo.getExplanation());
        assertEquals(100, demo.getLimit());
        assertEquals(2.0, demo.getBurstMultiplier(), 0.01);
        assertEquals(200, demo.getTotalRequestsInShortPeriod());
        assertNotNull(demo.getVisualExample());
        assertNotNull(demo.getRecommendation());
    }
}
