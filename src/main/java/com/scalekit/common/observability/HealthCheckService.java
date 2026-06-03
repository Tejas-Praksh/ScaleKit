package com.scalekit.common.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private final UrlShortenerHealthIndicator urlShortenerHealthIndicator;
    private final RateLimiterHealthIndicator rateLimiterHealthIndicator;
    private final CacheHealthIndicator cacheHealthIndicator;
    private final ConsistentHashHealthIndicator consistentHashHealthIndicator;
    private final BloomFilterHealthIndicator bloomFilterHealthIndicator;

    public Map<String, Health> getAllComponentHealth() {
        Map<String, Health> healthMap = new HashMap<>();
        healthMap.put("urlShortener", urlShortenerHealthIndicator.health());
        healthMap.put("rateLimiter", rateLimiterHealthIndicator.health());
        healthMap.put("cache", cacheHealthIndicator.health());
        healthMap.put("consistentHash", consistentHashHealthIndicator.health());
        healthMap.put("bloomFilter", bloomFilterHealthIndicator.health());
        return healthMap;
    }
}
