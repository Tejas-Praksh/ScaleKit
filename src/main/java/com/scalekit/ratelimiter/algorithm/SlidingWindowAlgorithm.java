package com.scalekit.ratelimiter.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core implementation of the Sliding Window rate limiting algorithm using Redis Sorted Sets.
 */
@Component
@Slf4j
public class SlidingWindowAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> redisScript;

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local request_id = ARGV[4]

            local window_start = now - window_ms

            -- Remove old requests outside window
            redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

            -- Count requests in window
            local count = redis.call('ZCARD', key)

            local allowed = 0
            local remaining = limit - count

            if count < limit then
              -- Add current request
              redis.call('ZADD', key, now, request_id)
              allowed = 1
              remaining = remaining - 1
            end

            -- Set expiry
            redis.call('EXPIRE', key, math.ceil(window_ms / 1000) + 1)

            -- Get oldest request time
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local reset_after = 0
            if #oldest > 0 then
              reset_after = tonumber(oldest[2]) + window_ms - now
            end

            return {allowed, remaining, math.max(0, reset_after)}
            """;

    public SlidingWindowAlgorithm(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.redisScript = RedisScript.of(LUA_SCRIPT, List.class);
    }

    public SlidingWindowResult tryConsume(String key, int limit, long windowSizeMs) {
        long startNanos = System.nanoTime();
        if (redisTemplate == null) {
            log.warn("Redis template is null. Failing open for key {}", key);
            return SlidingWindowResult.builder()
                    .allowed(true)
                    .remainingRequests(limit)
                    .limit(limit)
                    .resetAfterMs(0L)
                    .windowSizeMs(windowSizeMs)
                    .requestCountInWindow(0)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }

        try {
            long now = System.currentTimeMillis();
            String requestId = UUID.randomUUID().toString();

            List<?> resultList = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowSizeMs),
                    String.valueOf(limit),
                    requestId
            );

            if (resultList == null || resultList.size() < 3) {
                throw new IllegalStateException("Invalid response from sliding window rate limiter Lua script");
            }

            long allowedVal = ((Number) resultList.get(0)).longValue();
            long remainingVal = ((Number) resultList.get(1)).longValue();
            long resetAfterMs = ((Number) resultList.get(2)).longValue();

            boolean allowed = (allowedVal == 1);
            long count = limit - remainingVal;

            return SlidingWindowResult.builder()
                    .allowed(allowed)
                    .remainingRequests((int) remainingVal)
                    .limit(limit)
                    .resetAfterMs(resetAfterMs)
                    .windowSizeMs(windowSizeMs)
                    .requestCountInWindow(count)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();

        } catch (Exception e) {
            log.error("Redis sliding window execution failed for key {}. Failing open.", key, e);
            return SlidingWindowResult.builder()
                    .allowed(true)
                    .remainingRequests(limit)
                    .limit(limit)
                    .resetAfterMs(0L)
                    .windowSizeMs(windowSizeMs)
                    .requestCountInWindow(0)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }
    }

    public long getRequestCount(String key, long windowSizeMs) {
        if (redisTemplate == null) {
            return 0;
        }
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - windowSizeMs;
            Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to get request count for key {}: {}", key, e.getMessage());
            return 0;
        }
    }

    public List<Long> getRequestTimestamps(String key, long windowSizeMs) {
        if (redisTemplate == null) {
            return Collections.emptyList();
        }
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - windowSizeMs;
            // Get all range with scores
            var typedTuples = redisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
            if (typedTuples == null) {
                return Collections.emptyList();
            }
            return typedTuples.stream()
                    .map(tuple -> tuple.getScore() != null ? tuple.getScore().longValue() : 0L)
                    .filter(score -> score >= windowStart && score <= now)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to get request timestamps for key {}: {}", key, e.getMessage());
            return Collections.emptyList();
        }
    }

    public void resetWindow(String key) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Failed to delete rate limit key {}: {}", key, e.getMessage());
            }
        }
    }

    public long getMemoryUsage(String key) {
        if (redisTemplate == null) {
            return 0L;
        }
        try {
            // Redis MEMORY USAGE command returns bytes or null if key does not exist
            byte[] rawKey = key.getBytes();
            List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                connection.execute("MEMORY", "USAGE".getBytes(), rawKey);
                return null;
            });
            if (results != null && !results.isEmpty() && results.get(0) != null) {
                return ((Number) results.get(0)).longValue();
            }
            return 0L;
        } catch (Exception e) {
            log.debug("MEMORY USAGE command failed or key does not exist: {}", e.getMessage());
            return 0L;
        }
    }
}
