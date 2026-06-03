package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.LockResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redlock algorithm implementation from scratch.
 * Operates across N Redis instances to provide highly available locks.
 */
@Component
@Slf4j
public class RedlockAlgorithm {

    private static final String ACQUIRE_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local ttl = tonumber(ARGV[2])

            if redis.call('SET', key, value, 'NX', 'PX', ttl) then
              return 1
            else
              local current = redis.call('GET', key)
              if current == value then
                return 1
              end
              return 0
            end
            """;

    private static final String RELEASE_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]

            if redis.call('GET', key) == value then
              redis.call('DEL', key)
              return 1
            else
              return 0
            end
            """;

    private static final String EXTEND_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local ttl = tonumber(ARGV[2])

            if redis.call('GET', key) == value then
              redis.call('PEXPIRE', key, ttl)
              return 1
            else
              return 0
            end
            """;

    private final RedisScript<Long> acquireScript = RedisScript.of(ACQUIRE_SCRIPT, Long.class);
    private final RedisScript<Long> releaseScript = RedisScript.of(RELEASE_SCRIPT, Long.class);
    private final RedisScript<Long> extendScript = RedisScript.of(EXTEND_SCRIPT, Long.class);

    private final List<RedisTemplate<String, String>> redisInstances;
    private final int quorum;
    private final Random random = new SecureRandom();
    private final AtomicLong fencingTokenCounter = new AtomicLong(System.currentTimeMillis());

    @Autowired
    public RedlockAlgorithm(List<RedisTemplate<String, String>> redisInstances) {
        this.redisInstances = redisInstances != null ? redisInstances : Collections.emptyList();
        this.quorum = (this.redisInstances.size() / 2) + 1;
    }

    public LockResult tryAcquire(String lockKey, long ttlMs, String ownerId) {
        long startTime = currentTimeMs();
        String lockValue = ownerId + ":" + UUID.randomUUID().toString();
        long fencingToken = fencingTokenCounter.incrementAndGet();

        if (redisInstances.isEmpty() || redisInstances.contains(null)) {
            return LockResult.builder()
                    .acquired(false)
                    .lockKey(lockKey)
                    .fencingToken(fencingToken)
                    .failureReason("No active Redis instances available")
                    .acquiredAt(java.time.Instant.now())
                    .build();
        }

        int acquired = 0;
        int totalNodes = redisInstances.size();

        for (RedisTemplate<String, String> instance : redisInstances) {
            try {
                if (executeAcquireScript(instance, lockKey, lockValue, ttlMs)) {
                    acquired++;
                }
            } catch (Exception e) {
                log.warn("Failed to acquire lock on Redis node for key '{}': {}", lockKey, e.getMessage());
            }
        }

        long elapsed = currentTimeMs() - startTime;
        long validityTime = ttlMs - elapsed - clockDrift();

        if (acquired >= quorum && validityTime > 0) {
            return LockResult.builder()
                    .acquired(true)
                    .lockKey(lockKey)
                    .lockValue(lockValue)
                    .fencingToken(fencingToken)
                    .validityTimeMs(validityTime)
                    .acquisitionTimeMs(elapsed)
                    .nodesAcquired(acquired)
                    .nodesTotal(totalNodes)
                    .acquiredAt(java.time.Instant.now())
                    .build();
        } else {
            // Rollback best-effort release of acquired locks
            releaseAll(lockKey, lockValue);
            return LockResult.builder()
                    .acquired(false)
                    .lockKey(lockKey)
                    .fencingToken(fencingToken)
                    .failureReason("Failed to acquire lock on majority of nodes")
                    .acquiredAt(java.time.Instant.now())
                    .build();
        }
    }

    public boolean release(String lockKey, String lockValue) {
        if (redisInstances.isEmpty()) {
            return false;
        }

        int releasedCount = 0;
        for (RedisTemplate<String, String> instance : redisInstances) {
            try {
                if (executeReleaseScript(instance, lockKey, lockValue)) {
                    releasedCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to release lock on Redis node for key '{}': {}", lockKey, e.getMessage());
            }
        }

        return releasedCount >= quorum;
    }

    public boolean extend(String lockKey, String lockValue, long additionalTtlMs) {
        if (redisInstances.isEmpty()) {
            return false;
        }

        int extendedCount = 0;
        for (RedisTemplate<String, String> instance : redisInstances) {
            try {
                if (executeExtendScript(instance, lockKey, lockValue, additionalTtlMs)) {
                    extendedCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to extend lock on Redis node for key '{}': {}", lockKey, e.getMessage());
            }
        }

        return extendedCount >= quorum;
    }

    private void releaseAll(String lockKey, String lockValue) {
        for (RedisTemplate<String, String> instance : redisInstances) {
            try {
                executeReleaseScript(instance, lockKey, lockValue);
            } catch (Exception e) {
                // Ignore, best effort release
            }
        }
    }

    private boolean executeAcquireScript(RedisTemplate<String, String> instance, String lockKey, String lockValue, long ttlMs) {
        if (instance == null) {
            throw new RuntimeException("Redis connection failed");
        }
        Long result = instance.execute(
                acquireScript,
                Collections.singletonList(lockKey),
                lockValue,
                String.valueOf(ttlMs)
        );
        return result != null && result == 1L;
    }

    private boolean executeReleaseScript(RedisTemplate<String, String> instance, String lockKey, String lockValue) {
        if (instance == null) {
            return false;
        }
        Long result = instance.execute(
                releaseScript,
                Collections.singletonList(lockKey),
                lockValue
        );
        return result != null && result == 1L;
    }

    private boolean executeExtendScript(RedisTemplate<String, String> instance, String lockKey, String lockValue, long ttlMs) {
        if (instance == null) {
            return false;
        }
        Long result = instance.execute(
                extendScript,
                Collections.singletonList(lockKey),
                lockValue,
                String.valueOf(ttlMs)
        );
        return result != null && result == 1L;
    }

    private long clockDrift() {
        return 2L; // 2ms conservative estimate
    }

    private long currentTimeMs() {
        return System.currentTimeMillis();
    }
}
