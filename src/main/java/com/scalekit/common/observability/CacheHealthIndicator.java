package com.scalekit.common.observability;

import com.scalekit.cache.service.LRUCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CacheHealthIndicator implements HealthIndicator {

    private final LRUCacheService lruCacheService;
    private final RedisConnectionFactory redisConnectionFactory;
    private final MetricsCollector metricsCollector;

    @Override
    public Health health() {
        boolean redisUp = false;
        try {
            redisUp = "PONG".equals(redisConnectionFactory.getConnection().ping());
        } catch (Exception e) {
            // Redis error
        }

        int lruCachesCount = lruCacheService.getAllCacheStats().size();

        long hits = metricsCollector.getCacheHits();
        long misses = metricsCollector.getCacheMisses();
        long total = hits + misses;
        double hitRate = total == 0 ? 1.0 : (double) hits / total;

        Health.Builder builder;
        if (total > 0 && hitRate < 0.5) {
            builder = Health.status(new Status("WARNING", "Cache hit rate is low"));
        } else if (redisUp) {
            builder = Health.up();
        } else {
            builder = Health.down().withDetail("redis", "DOWN");
        }

        return builder
                .withDetail("lruCachesCount", lruCachesCount)
                .withDetail("lfuCachesCount", 0) // LFU cache size placeholder
                .withDetail("hitRate", hitRate)
                .withDetail("redis", redisUp ? "UP" : "DOWN")
                .build();
    }
}
