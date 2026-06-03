package com.scalekit.common.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RateLimiterHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public Health health() {
        long start = System.nanoTime();
        boolean redisUp = false;
        try {
            redisUp = "PONG".equals(redisConnectionFactory.getConnection().ping());
        } catch (Exception e) {
            // Redis connection error
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        if (redisUp) {
            return Health.up()
                    .withDetail("redis", "UP")
                    .withDetail("responseTimeMs", elapsedMs)
                    .build();
        } else {
            return Health.down()
                    .withDetail("redis", "DOWN")
                    .withDetail("responseTimeMs", elapsedMs)
                    .build();
        }
    }
}
