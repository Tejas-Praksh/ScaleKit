package com.scalekit.tests;

import com.scalekit.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.scalekit.ratelimiter.algorithm.FixedWindowResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Fixed Window Correctness Tests")
public class FixedWindowCorrectnessTest {

    @Test
    void fixedWindowFailOpenWithoutRedis() {
        FixedWindowAlgorithm algo = new FixedWindowAlgorithm(null);
        String key = "test:" + UUID.randomUUID();
        
        FixedWindowResult result = algo.tryConsume(key, 10, 60);
        assertTrue(result.isAllowed(), "Should fail open when Redis is absent");
        assertEquals(10, result.getRemainingRequests());
    }
}
