package com.scalekit.ratelimiter.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Core implementation of the Token Bucket rate limiting algorithm using Redis Lua scripts.
 */
@Component
@Slf4j
public class TokenBucketAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> redisScript;

    public static final String BUCKET_KEY_PREFIX = "tb:";

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')

            local tokens = tonumber(bucket[1])
            local last_refill = tonumber(bucket[2])

            if tokens == nil then
              tokens = capacity
              last_refill = now
            end

            local elapsed = (now - last_refill) / 1000.0
            local new_tokens = math.min(capacity, tokens + (elapsed * refill_rate))

            local allowed = 0
            if new_tokens >= requested then
              new_tokens = new_tokens - requested
              allowed = 1
            end

            local total_requests = tonumber(redis.call('HGET', key, 'total_requests') or 0) + 1
            local total_rejected = tonumber(redis.call('HGET', key, 'total_rejected') or 0)
            if allowed == 0 then
              total_rejected = total_rejected + 1
            end

            redis.call('HMSET', key,
              'tokens', new_tokens,
              'last_refill', now,
              'total_requests', total_requests,
              'total_rejected', total_rejected)
            redis.call('EXPIRE', key, 3600)

            return {allowed, math.floor(new_tokens), math.floor(capacity)}
            """;

    public TokenBucketAlgorithm(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.redisScript = RedisScript.of(LUA_SCRIPT, List.class);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BucketState {
        private double tokens;
        private long lastRefillTimestamp;
        private long totalRequests;
        private long totalRejected;
    }

    public TokenBucketResult tryConsume(String key, double capacity, double refillRatePerSecond) {
        long startNanos = System.nanoTime();
        if (redisTemplate == null) {
            log.warn("Redis template is null. Failing open for key {}", key);
            return TokenBucketResult.builder()
                    .allowed(true)
                    .remainingTokens((int) capacity)
                    .totalCapacity((int) capacity)
                    .retryAfterMs(0L)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }

        try {
            long now = System.currentTimeMillis();
            List<?> resultList = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerSecond),
                    String.valueOf(now),
                    "1"
            );

            if (resultList == null || resultList.size() < 3) {
                throw new IllegalStateException("Invalid response from rate limiter Lua script");
            }

            long allowedVal = ((Number) resultList.get(0)).longValue();
            long remainingVal = ((Number) resultList.get(1)).longValue();
            long capacityVal = ((Number) resultList.get(2)).longValue();

            boolean allowed = (allowedVal == 1);
            long retryAfterMs = 0L;
            if (!allowed) {
                double currentTokens = remainingVal;
                try {
                    String tokStr = (String) redisTemplate.opsForHash().get(key, "tokens");
                    if (tokStr != null) {
                        currentTokens = Double.parseDouble(tokStr);
                    }
                } catch (Exception ignored) {}

                retryAfterMs = (long) Math.max(0, ((1.0 - currentTokens) / refillRatePerSecond) * 1000.0);
            }

            return TokenBucketResult.builder()
                    .allowed(allowed)
                    .remainingTokens((int) remainingVal)
                    .totalCapacity((int) capacityVal)
                    .retryAfterMs(retryAfterMs)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();

        } catch (Exception e) {
            log.error("Redis token bucket execution failed for key {}. Failing open.", key, e);
            return TokenBucketResult.builder()
                    .allowed(true)
                    .remainingTokens((int) capacity)
                    .totalCapacity((int) capacity)
                    .retryAfterMs(0L)
                    .key(key)
                    .executionTimeNanos(System.nanoTime() - startNanos)
                    .build();
        }
    }

    public double getRemainingTokens(String key) {
        if (redisTemplate == null) {
            return 0.0;
        }
        try {
            String val = (String) redisTemplate.opsForHash().get(key, "tokens");
            return val != null ? Double.parseDouble(val) : 0.0;
        } catch (Exception e) {
            log.warn("Failed to get remaining tokens for key {}: {}", key, e.getMessage());
            return 0.0;
        }
    }

    public void resetBucket(String key) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Failed to delete rate limit key {}: {}", key, e.getMessage());
            }
        }
    }

    public Optional<BucketState> getBucketStats(String key) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            List<Object> fields = redisTemplate.<String, Object>opsForHash().multiGet(key,
                    Arrays.asList("tokens", "last_refill", "total_requests", "total_rejected"));
            if (fields == null || fields.isEmpty() || fields.get(0) == null) {
                return Optional.empty();
            }
            double tokens = Double.parseDouble(fields.get(0).toString());
            long lastRefill = Long.parseLong(fields.get(1).toString());
            long totalRequests = fields.get(2) != null ? Long.parseLong(fields.get(2).toString()) : 0L;
            long totalRejected = fields.get(3) != null ? Long.parseLong(fields.get(3).toString()) : 0L;

            return Optional.of(BucketState.builder()
                    .tokens(tokens)
                    .lastRefillTimestamp(lastRefill)
                    .totalRequests(totalRequests)
                    .totalRejected(totalRejected)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to get bucket stats for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public long getMemoryUsage(String key) {
        if (redisTemplate == null) {
            return 0L;
        }
        try {
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
