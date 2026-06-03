package com.scalekit.ratelimiter.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Core implementation of the Fixed Window Counter rate limiting algorithm using Redis INCR.
 *
 * <p>The simplest of all rate limiting algorithms. Uses a single Redis key per client
 * with an atomic INCR + EXPIRE Lua script. Extremely memory-efficient (O(1) per client)
 * but vulnerable to the <b>boundary burst problem</b>: a client can consume 2× the limit
 * across a window boundary.</p>
 */
@Component
@Slf4j
public class FixedWindowAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> redisScript;

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window_seconds = tonumber(ARGV[2])

            local count = redis.call('INCR', key)

            if count == 1 then
              redis.call('EXPIRE', key, window_seconds)
            end

            local allowed = 0
            local remaining = 0

            if count <= limit then
              allowed = 1
              remaining = limit - count
            end

            local ttl = redis.call('TTL', key)

            return {allowed, remaining, count, ttl}
            """;

    public FixedWindowAlgorithm(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.redisScript = RedisScript.of(LUA_SCRIPT, List.class);
    }

    public FixedWindowResult tryConsume(String key, int limit, int windowSeconds) {
        long startNanos = System.nanoTime();

        if (redisTemplate == null) {
            log.warn("Redis template is null. Failing open for key {}", key);
            return FixedWindowResult.builder()
                    .allowed(true)
                    .remainingRequests(limit)
                    .limit(limit)
                    .currentCount(0)
                    .windowTtlSeconds(windowSeconds)
                    .approachingBoundary(false)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }

        try {
            List<?> resultList = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            if (resultList == null || resultList.size() < 4) {
                throw new IllegalStateException("Invalid response from fixed window rate limiter Lua script");
            }

            long allowedVal = ((Number) resultList.get(0)).longValue();
            long remainingVal = ((Number) resultList.get(1)).longValue();
            long countVal = ((Number) resultList.get(2)).longValue();
            long ttlVal = ((Number) resultList.get(3)).longValue();

            boolean allowed = (allowedVal == 1);
            boolean approachingBoundary = countVal > limit * 0.9;

            if (approachingBoundary && allowed) {
                log.warn("Fixed Window key {} approaching boundary: count={}/{} ttl={}s",
                        key, countVal, limit, ttlVal);
            }

            return FixedWindowResult.builder()
                    .allowed(allowed)
                    .remainingRequests(Math.max(0, (int) remainingVal))
                    .limit(limit)
                    .currentCount(countVal)
                    .windowTtlSeconds(ttlVal)
                    .approachingBoundary(approachingBoundary)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();

        } catch (Exception e) {
            log.error("Redis fixed window execution failed for key {}. Failing open.", key, e);
            return FixedWindowResult.builder()
                    .allowed(true)
                    .remainingRequests(limit)
                    .limit(limit)
                    .currentCount(0)
                    .windowTtlSeconds(windowSeconds)
                    .approachingBoundary(false)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }
    }

    /**
     * Programmatically demonstrates the 2x boundary burst problem inherent to fixed windows.
     */
    public BoundaryProblemDemo demonstrateBoundaryProblem(String key, int limit) {
        return BoundaryProblemDemo.builder()
                .explanation(
                        "The Fixed Window algorithm divides time into discrete intervals. " +
                        "A client can send " + limit + " requests at the very end of one window, " +
                        "then " + limit + " more at the start of the next window — totalling " +
                        (limit * 2) + " requests in a very short period despite a limit of " + limit + ".")
                .limit(limit)
                .requestsEndOfWindow(limit)
                .requestsStartOfWindow(limit)
                .totalRequestsInShortPeriod(limit * 2)
                .burstMultiplier(2.0)
                .recommendation(
                        "Use Sliding Window for strict rate enforcement (payment, OTP, login APIs). " +
                        "Fixed Window is acceptable for content APIs where a brief 2x spike is tolerable.")
                .visualExample(
                        "|----Window 1----|----Window 2----|\n" +
                        "...........||||||||||||||||..........\n" +
                        "          ^^^ 2x burst here ^^^")
                .build();
    }

    public void resetWindow(String key) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Failed to reset fixed window key {}: {}", key, e.getMessage());
            }
        }
    }

    public long getCurrentCount(String key) {
        if (redisTemplate == null) return 0;
        try {
            String val = redisTemplate.opsForValue().get(key);
            return val != null ? Long.parseLong(val) : 0;
        } catch (Exception e) {
            log.warn("Failed to get current count for key {}: {}", key, e.getMessage());
            return 0;
        }
    }

    public long getWindowTtl(String key) {
        if (redisTemplate == null) return 0;
        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl != null ? ttl : 0;
        } catch (Exception e) {
            log.warn("Failed to get TTL for key {}: {}", key, e.getMessage());
            return 0;
        }
    }

    public long getMemoryUsage(String key) {
        if (redisTemplate == null) return 0L;
        try {
            byte[] rawKey = key.getBytes();
            List<Object> results = redisTemplate.executePipelined(
                    (org.springframework.data.redis.connection.RedisConnection connection) -> {
                        connection.execute("MEMORY", "USAGE".getBytes(), rawKey);
                        return null;
                    });
            if (results != null && !results.isEmpty() && results.get(0) != null) {
                return ((Number) results.get(0)).longValue();
            }
            return 0L;
        } catch (Exception e) {
            log.debug("MEMORY USAGE command failed for key {}: {}", key, e.getMessage());
            return 0L;
        }
    }
}
