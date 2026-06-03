package com.scalekit.common.observability;

import com.scalekit.common.dto.AlertDto;
import com.scalekit.common.dto.DashboardSnapshot;
import com.scalekit.common.gateway.CircuitBreakerRegistry;
import com.scalekit.common.gateway.CircuitBreakerStats;
import com.scalekit.cache.service.UrlDuplicateDetector;
import com.scalekit.cache.dto.UrlDuplicateStats;
import com.scalekit.cache.dto.BloomFilterStats;
import com.scalekit.cache.service.MessageQueueService;
import com.scalekit.cache.dto.QueueStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class DashboardDataServiceTest {

    @Autowired
    private DashboardDataService dashboardDataService;

    @MockBean
    private MetricsCollector metricsCollector;

    @MockBean
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockBean
    private UrlDuplicateDetector urlDuplicateDetector;

    @MockBean
    private MessageQueueService messageQueueService;

    @BeforeEach
    void setUp() {
        // Provide normal default mock returns
        when(metricsCollector.getErrorRate()).thenReturn(0.01);
        when(metricsCollector.getCurrentQPS()).thenReturn(5.0);
        
        UrlDuplicateStats mockBloomStats = UrlDuplicateStats.builder()
                .urlsAdded(10)
                .duplicatesBlocked(1)
                .estimatedFPR(0.001)
                .filterStats(BloomFilterStats.builder()
                        .currentFillRatio(0.1)
                        .bitArraySize(1000)
                        .insertedCount(10)
                        .build())
                .build();
        when(urlDuplicateDetector.getStats()).thenReturn(mockBloomStats);

        when(messageQueueService.getAllStats()).thenReturn(Map.of());
        when(circuitBreakerRegistry.getAllStats()).thenReturn(Map.of());
    }

    @Test
    void getDashboardSnapshot_returnsAllMetrics() {
        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        assertNotNull(snapshot);
        assertNotNull(snapshot.getTimestamp());
        assertNotNull(snapshot.getSystem());
        assertNotNull(snapshot.getUrls());
        assertNotNull(snapshot.getRateLimiter());
        assertNotNull(snapshot.getCache());
        assertNotNull(snapshot.getHashRing());
        assertNotNull(snapshot.getBloomFilter());
        assertNotNull(snapshot.getQueue());
        assertNotNull(snapshot.getActiveAlerts());
    }

    @Test
    void alerts_highErrorRate_firesAlert() {
        // Error rate > 5% triggers CRITICAL alert
        when(metricsCollector.getErrorRate()).thenReturn(0.06); // 6%

        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        List<AlertDto> alerts = snapshot.getActiveAlerts();

        boolean found = alerts.stream().anyMatch(a ->
                "CRITICAL".equals(a.getType()) && a.getMessage().toLowerCase().contains("error rate"));
        assertTrue(found, "Should trigger a CRITICAL error rate alert");
    }

    @Test
    void alerts_bloomFilterFull_firesWarning() {
        // Bloom filter > 80% full triggers WARNING alert
        UrlDuplicateStats mockBloomStats = UrlDuplicateStats.builder()
                .urlsAdded(1000)
                .duplicatesBlocked(5)
                .estimatedFPR(0.001)
                .filterStats(BloomFilterStats.builder()
                        .currentFillRatio(0.85) // 85%
                        .bitArraySize(1000)
                        .insertedCount(850)
                        .build())
                .build();
        when(urlDuplicateDetector.getStats()).thenReturn(mockBloomStats);

        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        List<AlertDto> alerts = snapshot.getActiveAlerts();

        boolean found = alerts.stream().anyMatch(a ->
                "WARNING".equals(a.getType()) && a.getMessage().contains("Bloom filter"));
        assertTrue(found, "Should trigger a WARNING bloom filter alert");
    }

    @Test
    void alerts_circuitOpen_firesCritical() {
        // Circuit breaker OPEN triggers CRITICAL alert
        CircuitBreakerStats openBreaker = CircuitBreakerStats.builder()
                .routeName("url-shortener")
                .state("OPEN")
                .failures(5)
                .build();
        when(circuitBreakerRegistry.getAllStats()).thenReturn(Map.of("url-shortener", openBreaker));

        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        List<AlertDto> alerts = snapshot.getActiveAlerts();

        boolean found = alerts.stream().anyMatch(a ->
                "CRITICAL".equals(a.getType()) && a.getMessage().contains("Circuit breaker") && a.getMessage().contains("OPEN"));
        assertTrue(found, "Should trigger a CRITICAL circuit breaker alert");
    }

    @Test
    void alerts_queueDLQHigh_firesWarning() {
        // Queue DLQ > 100 triggers WARNING alert
        QueueStats stats = QueueStats.builder()
                .queueName("main-queue")
                .totalDLQ(150)
                .currentDLQSize(150)
                .build();
        when(messageQueueService.getAllStats()).thenReturn(Map.of("main-queue", stats));

        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        List<AlertDto> alerts = snapshot.getActiveAlerts();

        boolean found = alerts.stream().anyMatch(a ->
                "WARNING".equals(a.getType()) && a.getMessage().contains("DLQ"));
        assertTrue(found, "Should trigger a WARNING DLQ overflow alert");
    }
}
