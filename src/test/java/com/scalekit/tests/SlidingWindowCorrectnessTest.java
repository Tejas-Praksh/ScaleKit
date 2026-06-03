package com.scalekit.tests;

import com.scalekit.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.scalekit.ratelimiter.algorithm.SlidingWindowResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Sliding Window Correctness Tests")
public class SlidingWindowCorrectnessTest {

    @Test
    void slidingWindowBasicBehavior() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        
        when(redisTemplate.execute(
            any(RedisScript.class),
            anyList(),
            any(), any(), any(), any()
        )).thenReturn(
            Arrays.asList(1L, 4L, 0L),
            Arrays.asList(1L, 3L, 0L),
            Arrays.asList(1L, 2L, 0L),
            Arrays.asList(1L, 1L, 0L),
            Arrays.asList(1L, 0L, 0L),
            Arrays.asList(0L, 0L, 1000L)
        );

        SlidingWindowAlgorithm algo = new SlidingWindowAlgorithm(redisTemplate);
        String key = "sliding:test";
        // Simulate 5 requests within window (limit 5 per minute)
        for (int i = 0; i < 5; i++) {
            SlidingWindowResult r = algo.tryConsume(key, 5, 60_000);
            assertTrue(r.isAllowed(), "Request " + i + " should be allowed");
        }
        // 6th request should be rejected
        assertFalse(algo.tryConsume(key, 5, 60_000).isAllowed(), "6th request should be blocked");
    }
}
