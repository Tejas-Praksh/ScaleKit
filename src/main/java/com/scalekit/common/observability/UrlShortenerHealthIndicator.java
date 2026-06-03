package com.scalekit.common.observability;

import com.scalekit.urlshortener.service.CounterService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
@RequiredArgsConstructor
public class UrlShortenerHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final CounterService counterService;

    @Override
    public Health health() {
        boolean dbUp = false;
        try (Connection conn = dataSource.getConnection()) {
            dbUp = conn.isValid(1);
        } catch (Exception e) {
            // DB Down
        }

        boolean redisUp = false;
        try {
            redisUp = "PONG".equals(redisConnectionFactory.getConnection().ping());
        } catch (Exception e) {
            // Redis Down
        }

        boolean counterUp = false;
        try {
            // Try a dummy identifier generation or check if counterService exists
            counterUp = (counterService != null);
        } catch (Exception e) {
            // Counter down
        }

        if (dbUp && redisUp && counterUp) {
            return Health.up()
                    .withDetail("database", "UP")
                    .withDetail("redis", "UP")
                    .withDetail("counterService", "UP")
                    .build();
        } else {
            Status status = (dbUp || redisUp) ? Status.UP : Status.DOWN; // UP but degraded if at least one works
            String serviceState = (dbUp && redisUp) ? "UP" : ((dbUp || redisUp) ? "DEGRADED" : "DOWN");
            return Health.status(status)
                    .withDetail("database", dbUp ? "UP" : "DOWN")
                    .withDetail("redis", redisUp ? "UP" : "DOWN")
                    .withDetail("counterService", serviceState)
                    .build();
        }
    }
}
