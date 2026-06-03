package com.scalekit.cache.algorithm;

import com.scalekit.common.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis-backed Bloom Filter for distributed membership testing.
 *
 * <p>Stores the bit array in Redis using {@code SETBIT}/{@code GETBIT} commands,
 * making it accessible across multiple application instances.
 *
 * <p>Gracefully degrades if Redis is unavailable — logs a warning and returns
 * {@code false} for {@code mightContain} (safe default: treat as "not seen").
 */
@Component
@Slf4j
public class RedisBloomFilter {

    private static final String KEY_PREFIX = "bloom:";

    private final StringRedisTemplate redis;
    private final int defaultBitArraySize;
    private final int defaultHashFunctionCount;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisBloomFilter(StringRedisTemplate redis) {
        this.redis = redis;
        // Defaults: ~1M expected insertions, 0.1% FPR
        // m = -(1000000 * ln(0.001)) / (ln(2))^2 ≈ 14,377,588
        this.defaultBitArraySize = 14_377_588;
        // k = (m/n) * ln(2) ≈ 10
        this.defaultHashFunctionCount = 10;
    }

    /**
     * Creates a filter descriptor with custom parameters (does not allocate Redis bits eagerly).
     */
    public RedisBloomFilter(StringRedisTemplate redis, int bitArraySize, int hashFunctionCount) {
        this.redis = redis;
        this.defaultBitArraySize = bitArraySize;
        this.defaultHashFunctionCount = hashFunctionCount;
    }

    /**
     * Adds an item to the named filter by setting bits in Redis.
     */
    public void add(String filterName, String item) {
        try {
            String key = KEY_PREFIX + filterName;
            for (int i = 0; i < defaultHashFunctionCount; i++) {
                int pos = getHashPosition(item, i, defaultBitArraySize);
                redis.opsForValue().setBit(key, pos, true);
            }
        } catch (Exception e) {
            log.warn("RedisBloomFilter.add failed for filter '{}': {}", filterName, e.getMessage());
        }
    }

    /**
     * Checks whether an item might exist in the named filter.
     *
     * @return {@code true} if all bits are set (possible false positive);
     *         {@code false} if any bit is unset (definitely not present),
     *         or if Redis is unavailable (safe default).
     */
    public boolean mightContain(String filterName, String item) {
        try {
            String key = KEY_PREFIX + filterName;
            for (int i = 0; i < defaultHashFunctionCount; i++) {
                int pos = getHashPosition(item, i, defaultBitArraySize);
                Boolean bit = redis.opsForValue().getBit(key, pos);
                if (bit == null || !bit) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("RedisBloomFilter.mightContain failed for filter '{}': {}", filterName, e.getMessage());
            return false; // safe default: treat as "not seen"
        }
    }

    /**
     * Deletes a named filter from Redis.
     */
    public void delete(String filterName) {
        try {
            redis.delete(KEY_PREFIX + filterName);
        } catch (Exception e) {
            log.warn("RedisBloomFilter.delete failed for filter '{}': {}", filterName, e.getMessage());
        }
    }

    /**
     * Returns the number of set bits (approximate) in the named filter.
     */
    public long getFilterBitCount(String filterName) {
        try {
            Long count = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>)
                    connection -> connection.stringCommands()
                            .bitCount((KEY_PREFIX + filterName).getBytes(StandardCharsets.UTF_8)));
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.warn("RedisBloomFilter.getFilterBitCount failed for filter '{}': {}", filterName, e.getMessage());
            return 0L;
        }
    }

    /**
     * Computes a bit-array position for the given item and seed.
     * Same hash strategy as {@link BloomFilter}.
     */
    private int getHashPosition(String item, int seed, int bitArraySize) {
        int hash;
        switch (seed) {
            case 0 -> hash = HashUtil.murmur3(item, 0);
            case 1 -> hash = HashUtil.murmur3(item, 1);
            case 2 -> hash = BloomFilter.fnv1a(item);
            case 3 -> hash = BloomFilter.djb2(item);
            default -> hash = HashUtil.murmur3(item, seed);
        }
        return Math.abs(hash) % bitArraySize;
    }
}
