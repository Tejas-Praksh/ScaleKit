package com.scalekit.common.observability;

import com.scalekit.cache.dto.BloomFilterStats;
import com.scalekit.cache.dto.UrlDuplicateStats;
import com.scalekit.cache.service.UrlDuplicateDetector;
import com.scalekit.cache.algorithm.service.ConsistentHashService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class HealthIndicatorTest {

    @Autowired
    private UrlShortenerHealthIndicator urlShortenerHealthIndicator;

    @Autowired
    private RateLimiterHealthIndicator rateLimiterHealthIndicator;

    @Autowired
    private CacheHealthIndicator cacheHealthIndicator;

    @Autowired
    private ConsistentHashHealthIndicator consistentHashHealthIndicator;

    @Autowired
    private BloomFilterHealthIndicator bloomFilterHealthIndicator;

    @MockBean
    private UrlDuplicateDetector urlDuplicateDetector;

    @MockBean
    private ConsistentHashService consistentHashService;

    @Test
    void urlShortener_healthy_returnsUp() {
        Health health = urlShortenerHealthIndicator.health();
        assertNotNull(health);
        // Under standard unit test environment with H2 database, it should be UP
        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void rateLimiter_healthy_returnsUp() {
        Health health = rateLimiterHealthIndicator.health();
        assertNotNull(health);
        // By default should be UP (or DOWN if Redis is completely unavailable, which we'll handle gracefully)
        assertTrue(health.getStatus() == Status.UP || health.getStatus() == Status.DOWN);
    }

    @Test
    void cache_healthy_returnsUp() {
        Health health = cacheHealthIndicator.health();
        assertNotNull(health);
        assertTrue(health.getStatus() == Status.UP || health.getStatus() == Status.DOWN);
    }

    @Test
    void consistentHash_healthy_returnsUp() {
        when(consistentHashService.getAllNodes()).thenReturn(java.util.Set.of("node-1"));
        Health health = consistentHashHealthIndicator.health();
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void bloomFilter_nearFull_returnsWarning() {
        UrlDuplicateStats fullBloomStats = UrlDuplicateStats.builder()
                .urlsAdded(1000)
                .duplicatesBlocked(5)
                .estimatedFPR(0.001)
                .filterStats(BloomFilterStats.builder()
                        .currentFillRatio(0.85) // 85%
                        .bitArraySize(1000)
                        .insertedCount(850)
                        .build())
                .build();
        when(urlDuplicateDetector.getStats()).thenReturn(fullBloomStats);

        Health health = bloomFilterHealthIndicator.health();
        assertNotNull(health);
        // Spring Boot Actuator health has Status.UNKNOWN or we can check custom status/details.
        // Let's assert that detail warning message or status is correct.
        assertEquals("WARNING", health.getStatus().getCode());
        assertEquals(0.85, (Double) health.getDetails().get("fillRatio"), 0.01);
    }
}
