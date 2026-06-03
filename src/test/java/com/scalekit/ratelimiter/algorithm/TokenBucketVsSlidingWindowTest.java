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

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class TokenBucketVsSlidingWindowTest {

    @Autowired(required = false)
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired(required = false)
    private SlidingWindowAlgorithm slidingWindowAlgorithm;

    private String tbKey;
    private String swKey;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
        assertNotNull(tokenBucketAlgorithm);
        assertNotNull(slidingWindowAlgorithm);
        tbKey = "tb:compare:" + UUID.randomUUID().toString();
        swKey = "sw:compare:" + UUID.randomUUID().toString();
    }

    @Test
    void burstBehavior_tokenBucketAllows_slidingWindowRejects() throws InterruptedException {
        // Setup: limit=10, windowSize=60s
        // Token Bucket: capacity=10, refill=10/60s (0.16667 tokens/sec)
        // Sliding Window: limit=10, windowSize=60000ms

        double tbCapacity = 10.0;
        double tbRefillRatePerSecond = 10.0 / 60.0; // 0.16667 tokens/sec (1 token every 6s)

        int swLimit = 10;
        long swWindowMs = 60000;

        // 1. Consume 10 requests immediately in both
        for (int i = 0; i < 10; i++) {
            assertTrue(tokenBucketAlgorithm.tryConsume(tbKey, tbCapacity, tbRefillRatePerSecond).isAllowed(), "TB allows initial burst");
            assertTrue(slidingWindowAlgorithm.tryConsume(swKey, swLimit, swWindowMs).isAllowed(), "SW allows initial burst");
        }

        // Both are now fully exhausted
        assertFalse(tokenBucketAlgorithm.tryConsume(tbKey, tbCapacity, tbRefillRatePerSecond).isAllowed(), "TB exhausted");
        assertFalse(slidingWindowAlgorithm.tryConsume(swKey, swLimit, swWindowMs).isAllowed(), "SW exhausted");

        // 2. Wait 7 seconds. 
        // Token Bucket: 7s * 0.16667 tokens/sec = 1.16 tokens refilled. Thus, we should have >1 token and allow 1 request!
        // Sliding Window: 7 seconds is < 60 seconds, so all 10 previous requests are still inside the window. It must REJECT!
        Thread.sleep(7000);

        assertTrue(tokenBucketAlgorithm.tryConsume(tbKey, tbCapacity, tbRefillRatePerSecond).isAllowed(), 
                "Token Bucket refilled 1 token after 7s and should ALLOW this request");
        
        assertFalse(slidingWindowAlgorithm.tryConsume(swKey, swLimit, swWindowMs).isAllowed(), 
                "Sliding Window is still within 60s window and should REJECT this request");
    }

    @Test
    void steadyStateComparison() throws InterruptedException {
        // Under steady state (1 request every 2 seconds, with limit=10 / 10s window size)
        // Refill rate for TB: 1 token/sec (capacity 10, refill rate 1.0)
        // Both should allow all requests because the rate matches or is within limits.
        
        double tbCapacity = 10.0;
        double tbRefillRatePerSecond = 1.0; // 1 token/sec
        
        int swLimit = 10;
        long swWindowMs = 10000; // 10s window

        for (int i = 0; i < 3; i++) {
            assertTrue(tokenBucketAlgorithm.tryConsume(tbKey, tbCapacity, tbRefillRatePerSecond).isAllowed());
            assertTrue(slidingWindowAlgorithm.tryConsume(swKey, swLimit, swWindowMs).isAllowed());
            Thread.sleep(1000);
        }
    }
}
