package com.scalekit.ratelimiter.config;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Configuration rules for endpoints rate limiting, mapped from application properties.
 */
@Component
@ConfigurationProperties(prefix = "rate-limit")
@Data
public class RateLimitRules {

    private Map<String, EndpointRule> endpoints;

    /**
     * Rules defined for a single API endpoint.
     */
    @Data
    public static class EndpointRule {
        private int requestsPerMinute;
        private int burstSize;
        private RateLimitAlgorithm algorithm;
        private String identifierType; // IP, USER, API_KEY, GLOBAL
        private boolean enabled = true;
    }
}
