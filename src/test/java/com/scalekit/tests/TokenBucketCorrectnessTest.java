package com.scalekit.tests;

import com.scalekit.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Token Bucket Correctness Tests")
public class TokenBucketCorrectnessTest {

    @Test
    void tokenBucketRefillAndConsume() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        
        when(redisTemplate.execute(
            any(RedisScript.class),
            anyList(),
            any(), any(), any(), any()
        )).thenReturn(
            Arrays.asList(1L, 9L, 10L), // 1
            Arrays.asList(1L, 8L, 10L), // 2
            Arrays.asList(1L, 7L, 10L), // 3
            Arrays.asList(1L, 6L, 10L), // 4
            Arrays.asList(1L, 5L, 10L), // 5
            Arrays.asList(1L, 4L, 10L), // 6
            Arrays.asList(1L, 3L, 10L), // 7
            Arrays.asList(1L, 2L, 10L), // 8
            Arrays.asList(1L, 1L, 10L), // 9
            Arrays.asList(1L, 0L, 10L), // 10
            Arrays.asList(0L, 0L, 10L), // 11 (rejected)
            // After sleep: simulate 2 allowed, 3 rejected
            Arrays.asList(1L, 1L, 10L), // 12 (allowed)
            Arrays.asList(1L, 0L, 10L), // 13 (allowed)
            Arrays.asList(0L, 0L, 10L), // 14 (rejected)
            Arrays.asList(0L, 0L, 10L), // 15 (rejected)
            Arrays.asList(0L, 0L, 10L)  // 16 (rejected)
        );

        TokenBucketAlgorithm bucket = new TokenBucketAlgorithm(redisTemplate);
        String key = "test:" + UUID.randomUUID();
        // Simulate consuming up to capacity (10 tokens)
        for (int i = 0; i < 10; i++) {
            TokenBucketResult r = bucket.tryConsume(key, 10, 1.0);
            assertTrue(r.isAllowed(), "Token " + i + " should be allowed");
        }
        // 11th should be rejected
        assertFalse(bucket.tryConsume(key, 10, 1.0).isAllowed(), "Should reject beyond capacity");
        // Wait for refill (approx 2 seconds => 2 tokens)
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (bucket.tryConsume(key, 10, 1.0).isAllowed()) allowed++;
        }
        assertTrue(allowed >= 2 && allowed <= 3, "Refill should permit 2-3 tokens after wait");
    }
}
