package com.scalekit.ratelimiter.algorithm;

import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class TokenBucketAlgorithmTest {

    @Autowired
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String key;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
        key = "tb:test:" + UUID.randomUUID().toString();
    }

    @Test
    void tryConsume_sufficientTokens_allows() {
        TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, 10, 1.0);
        assertTrue(result.isAllowed());
        assertEquals(9, result.getRemainingTokens());
        assertEquals(10, result.getTotalCapacity());
        assertEquals(0L, result.getRetryAfterMs());
    }

    @Test
    void tryConsume_insufficientTokens_rejects() {
        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            tokenBucketAlgorithm.tryConsume(key, 5, 1.0);
        }

        // Try one more
        TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, 5, 1.0);
        assertFalse(result.isAllowed());
        assertEquals(0, result.getRemainingTokens());
        assertTrue(result.getRetryAfterMs() > 0);
    }

    @Test
    void tryConsume_refillsOverTime() throws InterruptedException {
        // Consume all
        for (int i = 0; i < 2; i++) {
            tokenBucketAlgorithm.tryConsume(key, 2, 2.0); // capacity 2, refill rate 2 tokens/sec
        }

        // Wait 500ms -> should refill ~1 token
        Thread.sleep(550);

        TokenBucketResult result = tokenBucketAlgorithm.tryConsume(key, 2, 2.0);
        assertTrue(result.isAllowed());
    }

    @Test
    void tryConsume_burstAllowed_thenRejected() {
        // burst allowed: consume 5 immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(tokenBucketAlgorithm.tryConsume(key, 5, 1.0).isAllowed());
        }
        // 6th should be rejected
        assertFalse(tokenBucketAlgorithm.tryConsume(key, 5, 1.0).isAllowed());
    }

    @Test
    void tryConsume_redisDown_failsOpen() {
        TokenBucketAlgorithm failingAlg = new TokenBucketAlgorithm(null);
        TokenBucketResult result = failingAlg.tryConsume("failing-key", 10.0, 1.0);
        assertTrue(result.isAllowed());
        assertEquals(10, result.getRemainingTokens());
    }

    @Test
    void resetBucket_clearsState() {
        tokenBucketAlgorithm.tryConsume(key, 5, 1.0);
        tokenBucketAlgorithm.resetBucket(key);

        Optional<TokenBucketAlgorithm.BucketState> stats = tokenBucketAlgorithm.getBucketStats(key);
        assertFalse(stats.isPresent());
    }
}
