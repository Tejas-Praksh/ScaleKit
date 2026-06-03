package com.scalekit.common.observability;

import com.scalekit.cache.algorithm.service.ConsistentHashService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsistentHashHealthIndicator implements HealthIndicator {

    private final ConsistentHashService consistentHashService;

    @Override
    public Health health() {
        int nodeCount = consistentHashService.getAllNodes().size();
        if (nodeCount > 0) {
            return Health.up()
                    .withDetail("nodeCount", nodeCount)
                    .build();
        } else {
            return Health.down()
                    .withDetail("nodeCount", 0)
                    .withDetail("message", "Hash ring has no physical nodes")
                    .build();
        }
    }
}
