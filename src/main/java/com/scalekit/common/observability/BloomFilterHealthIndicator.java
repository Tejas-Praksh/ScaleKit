package com.scalekit.common.observability;

import com.scalekit.cache.dto.UrlDuplicateStats;
import com.scalekit.cache.service.UrlDuplicateDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BloomFilterHealthIndicator implements HealthIndicator {

    private final UrlDuplicateDetector urlDuplicateDetector;

    @Override
    public Health health() {
        UrlDuplicateStats stats = urlDuplicateDetector.getStats();
        if (stats == null || stats.getFilterStats() == null) {
            return Health.unknown().build();
        }

        double fillRatio = stats.getFilterStats().getCurrentFillRatio();
        Health.Builder builder;
        if (fillRatio > 0.8) {
            builder = Health.status(new Status("WARNING", "Bloom filter is near capacity"));
        } else {
            builder = Health.up();
        }

        return builder
                .withDetail("fillRatio", fillRatio)
                .withDetail("urlsAdded", stats.getUrlsAdded())
                .withDetail("duplicatesBlocked", stats.getDuplicatesBlocked())
                .withDetail("estimatedFPR", stats.getEstimatedFPR())
                .build();
    }
}
