package com.scalekit.common.config;

import com.scalekit.common.constants.SystemConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Prometheus metrics registration.
 *
 * <p>Registers counters, gauges, timers, and distribution summaries
 * for URL shortener, rate limiter, and cache subsystems.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter urlCreatedCounter(MeterRegistry registry) {
        return Counter.builder(SystemConstants.METRIC_URL_CREATED)
                .description("Total URLs shortened")
                .register(registry);
    }

    @Bean
    public Counter urlRedirectedCounter(MeterRegistry registry) {
        return Counter.builder(SystemConstants.METRIC_URL_REDIRECTED)
                .description("Total URL redirects served")
                .register(registry);
    }

    @Bean
    public Counter rateLimitExceededCounter(MeterRegistry registry) {
        return Counter.builder(SystemConstants.METRIC_RATE_LIMIT_HIT)
                .description("Total rate limit exceeded events")
                .register(registry);
    }

    @Bean
    public Counter cacheHitCounter(MeterRegistry registry) {
        return Counter.builder(SystemConstants.METRIC_CACHE_HIT)
                .description("Total cache hits")
                .register(registry);
    }

    @Bean
    public Counter cacheMissCounter(MeterRegistry registry) {
        return Counter.builder(SystemConstants.METRIC_CACHE_MISS)
                .description("Total cache misses")
                .register(registry);
    }

    @Bean
    public AtomicLong cacheSizeGauge(MeterRegistry registry) {
        AtomicLong cacheSize = new AtomicLong(0);
        Gauge.builder("scalekit.cache.size", cacheSize, AtomicLong::doubleValue)
                .description("Current cache size")
                .register(registry);
        return cacheSize;
    }

    @Bean
    public AtomicLong activeConnectionsGauge(MeterRegistry registry) {
        AtomicLong activeConnections = new AtomicLong(0);
        Gauge.builder("scalekit.active.connections", activeConnections, AtomicLong::doubleValue)
                .description("Current active connections")
                .register(registry);
        return activeConnections;
    }

    @Bean
    public Timer urlRedirectTimer(MeterRegistry registry) {
        return Timer.builder("scalekit.url.redirect.duration")
                .description("URL redirect latency")
                .publishPercentileHistogram()
                .register(registry);
    }

    @Bean
    public Timer rateLimitCheckTimer(MeterRegistry registry) {
        return Timer.builder("scalekit.ratelimit.check.duration")
                .description("Rate limit check latency")
                .publishPercentileHistogram()
                .register(registry);
    }

    @Bean
    public DistributionSummary urlCodeLengthSummary(MeterRegistry registry) {
        return DistributionSummary.builder("scalekit.url.code.length")
                .description("Distribution of URL short code lengths")
                .register(registry);
    }

    // ── URL Safety Metrics ──────────────────────────────────────────────────
    @Bean
    public Counter safetyChecksTotalCounter(MeterRegistry registry) {
        return Counter.builder("scalekit.safety.checks.total")
                .description("Total URL safety checks performed")
                .register(registry);
    }

    @Bean
    public Counter safetyChecksBlockedCounter(MeterRegistry registry) {
        return Counter.builder("scalekit.safety.checks.blocked")
                .description("Total URLs blocked by safety checker")
                .register(registry);
    }

    @Bean
    public Counter safetyChecksWarnedCounter(MeterRegistry registry) {
        return Counter.builder("scalekit.safety.checks.warned")
                .description("Total URLs warned by safety checker")
                .register(registry);
    }

    @Bean
    public Counter safetyChecksSafeCounter(MeterRegistry registry) {
        return Counter.builder("scalekit.safety.checks.safe")
                .description("Total URLs marked safe by safety checker")
                .register(registry);
    }

    @Bean
    public Counter safetyCacheHitsCounter(MeterRegistry registry) {
        return Counter.builder("scalekit.safety.cache.hits")
                .description("Total safety cache hits")
                .register(registry);
    }

    @Bean
    public Timer safetyCheckDurationTimer(MeterRegistry registry) {
        return Timer.builder("scalekit.safety.check.duration")
                .description("URL safety check duration")
                .publishPercentileHistogram()
                .register(registry);
    }
}

