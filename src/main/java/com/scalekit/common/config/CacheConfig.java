package com.scalekit.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.scalekit.common.constants.SystemConstants;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Two-level cache configuration.
 *
 * <p>Level 1: Caffeine (in-memory, sub-millisecond, per-JVM)
 * Level 2: Redis (distributed, millisecond, shared across instances)
 *
 * <p>The Caffeine cache is marked {@code @Primary} so {@code @Cacheable}
 * annotations use it by default. Services that need distributed caching
 * can inject the Redis cache manager by qualifier.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Level 1: Caffeine in-memory cache.
     * Max 10,000 entries, 5-minute TTL, LRU eviction.
     */
    @Bean
    @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "urls", "rateLimits", "analytics");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(SystemConstants.MAX_CACHE_SIZE)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats());
        return manager;
    }

    /**
     * Level 2: Redis distributed cache.
     * 1-hour TTL, JSON serialization.
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(SystemConstants.DEFAULT_CACHE_TTL_SECONDS))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
