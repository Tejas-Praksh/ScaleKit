package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.scalekit.ratelimiter.algorithm.SlidingWindowResult;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlidingWindowServiceTest {

    private SlidingWindowService slidingWindowService;

    @Mock private SlidingWindowAlgorithm slidingWindowAlgorithm;
    @Mock private RateLimitRules rateLimitRules;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;

    @BeforeEach
    void setUp() {
        // Mock micrometer registry to return a mock counter
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        slidingWindowService = new SlidingWindowService(slidingWindowAlgorithm, rateLimitRules, meterRegistry);

        Map<String, RateLimitRules.EndpointRule> endpoints = new HashMap<>();
        RateLimitRules.EndpointRule rule = new RateLimitRules.EndpointRule();
        rule.setRequestsPerMinute(60);
        rule.setBurstSize(0); // no burst for sliding window
        rule.setAlgorithm(RateLimitAlgorithm.SLIDING_WINDOW);
        rule.setIdentifierType("IP");
        rule.setEnabled(true);
        endpoints.put("url-create", rule);
        endpoints.put("api-global", rule);

        lenient().when(rateLimitRules.getEndpoints()).thenReturn(endpoints);
    }

    @Test
    void isAllowed_withinWindow_returnsAllowed() {
        SlidingWindowResult algResult = SlidingWindowResult.builder()
                .allowed(true)
                .remainingRequests(59)
                .limit(60)
                .resetAfterMs(0)
                .windowSizeMs(60000)
                .requestCountInWindow(1)
                .key("sw:url-create:192.168.1.1")
                .build();

        when(slidingWindowAlgorithm.tryConsume(eq("sw:url-create:192.168.1.1"), eq(60), eq(60000L)))
                .thenReturn(algResult);

        RateLimitResponse response = slidingWindowService.isAllowed("192.168.1.1", "url-create");

        assertTrue(response.isAllowed());
        assertEquals(59, response.getRemainingRequests());
        assertEquals(60, response.getLimitPerMinute());
        assertEquals(0L, response.getRetryAfterMs());
        verify(counter, atLeastOnce()).increment();
    }

    @Test
    void isAllowed_overWindow_returnsRejected() {
        SlidingWindowResult algResult = SlidingWindowResult.builder()
                .allowed(false)
                .remainingRequests(0)
                .limit(60)
                .resetAfterMs(45000)
                .windowSizeMs(60000)
                .requestCountInWindow(60)
                .key("sw:url-create:10.0.0.1")
                .build();

        when(slidingWindowAlgorithm.tryConsume(eq("sw:url-create:10.0.0.1"), eq(60), eq(60000L)))
                .thenReturn(algResult);

        RateLimitResponse response = slidingWindowService.isAllowed("10.0.0.1", "url-create");

        assertFalse(response.isAllowed());
        assertEquals(0, response.getRemainingRequests());
        assertEquals(45000L, response.getRetryAfterMs());
    }
}
