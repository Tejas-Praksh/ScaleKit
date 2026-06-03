package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.LockResult;
import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class RedlockAlgorithmTest {

    @Autowired
    private RedlockAlgorithm redlockAlgorithm;

    private String lockKey;
    private String ownerId;

    @BeforeEach
    void setUp() {
        lockKey = "lock:test:" + UUID.randomUUID().toString();
        ownerId = "owner-" + UUID.randomUUID().toString();
    }

    @Test
    void tryAcquire_available_succeeds() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        LockResult result = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
        assertTrue(result.getFencingToken() > 0);
        assertTrue(result.getValidityTimeMs() > 0);
        assertEquals(1, result.getNodesAcquired());
        
        // Clean up
        redlockAlgorithm.release(lockKey, result.getLockValue());
    }

    @Test
    void tryAcquire_alreadyLocked_fails() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        LockResult result1 = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result1.isAcquired());

        String otherOwner = "owner-other";
        LockResult result2 = redlockAlgorithm.tryAcquire(lockKey, 5000, otherOwner);
        assertFalse(result2.isAcquired());
        assertEquals("Failed to acquire lock on majority of nodes", result2.getFailureReason());

        // Clean up
        redlockAlgorithm.release(lockKey, result1.getLockValue());
    }

    @Test
    void tryAcquire_returnsUniqueLockValue() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        LockResult result1 = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result1.isAcquired());
        
        redlockAlgorithm.release(lockKey, result1.getLockValue());

        LockResult result2 = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result2.isAcquired());
        assertNotEquals(result1.getLockValue(), result2.getLockValue());

        // Clean up
        redlockAlgorithm.release(lockKey, result2.getLockValue());
    }

    @Test
    void release_validValue_releases() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        LockResult result = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result.isAcquired());

        boolean released = redlockAlgorithm.release(lockKey, result.getLockValue());
        assertTrue(released);

        // Can acquire again
        LockResult result2 = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result2.isAcquired());

        // Clean up
        redlockAlgorithm.release(lockKey, result2.getLockValue());
    }

    @Test
    void release_invalidValue_fails() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        LockResult result = redlockAlgorithm.tryAcquire(lockKey, 5000, ownerId);
        assertTrue(result.isAcquired());

        // Try releasing with wrong value
        boolean released = redlockAlgorithm.release(lockKey, "wrong-value");
        assertFalse(released);

        // Lock is still held by original owner
        LockResult result2 = redlockAlgorithm.tryAcquire(lockKey, 5000, "other-owner");
        assertFalse(result2.isAcquired());

        // Clean up
        redlockAlgorithm.release(lockKey, result.getLockValue());
    }

    @Test
    void fencingToken_monotonicallyIncreasing() {
        long prevToken = 0;
        for (int i = 0; i < 10; i++) {
            String tempKey = "lock:temp:" + UUID.randomUUID().toString();
            // Just use tryAcquire, doesn't matter if it fails or succeeds, token count increases
            LockResult result = redlockAlgorithm.tryAcquire(tempKey, 100, ownerId);
            long token = result.getFencingToken();
            if (i > 0) {
                assertTrue(token > prevToken, "Fencing token should monotonically increase");
            }
            prevToken = token;
            if (result.isAcquired()) {
                redlockAlgorithm.release(tempKey, result.getLockValue());
            }
        }
    }

    @Test
    void tryAcquire_redisDown_failsGracefully() {
        // Create an algorithm instance with a failing Redis instance or null template
        RedlockAlgorithm failingAlg = new RedlockAlgorithm(Collections.singletonList(null));
        
        LockResult result = failingAlg.tryAcquire(lockKey, 5000, ownerId);
        assertFalse(result.isAcquired());
        assertNotNull(result.getFailureReason());
        assertTrue(result.getFailureReason().contains("majority") || result.getFailureReason().contains("NullPointerException") || result.getFailureReason().contains("Redis connection failed") || result.getFailureReason().contains("No active Redis instances"));
    }
}
