package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.scalekit.ratelimiter.algorithm.TokenBucketResult;
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
class TokenBucketServiceTest {

    private TokenBucketService tokenBucketService;

    @Mock
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Mock
    private RateLimitRules rateLimitRules;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        Map<String, RateLimitRules.EndpointRule> endpoints = new HashMap<>();

        RateLimitRules.EndpointRule createRule = new RateLimitRules.EndpointRule();
        createRule.setRequestsPerMinute(60);
        createRule.setBurstSize(20);
        createRule.setAlgorithm(RateLimitAlgorithm.TOKEN_BUCKET);
        createRule.setIdentifierType("IP");
        createRule.setEnabled(true);
        endpoints.put("url-create", createRule);

        RateLimitRules.EndpointRule redirectRule = new RateLimitRules.EndpointRule();
        redirectRule.setRequestsPerMinute(600);
        redirectRule.setBurstSize(100);
        redirectRule.setAlgorithm(RateLimitAlgorithm.TOKEN_BUCKET);
        redirectRule.setIdentifierType("IP");
        redirectRule.setEnabled(true);
        endpoints.put("url-redirect", redirectRule);

        lenient().when(rateLimitRules.getEndpoints()).thenReturn(endpoints);

        tokenBucketService = new TokenBucketService(tokenBucketAlgorithm, rateLimitRules, meterRegistry);
    }

    @Test
    void isAllowed_withinLimit_returnsAllowed() {
        String identifier = "192.168.1.100";
        String endpoint = "url-create";
        String key = "tb:url-create:" + identifier;

        TokenBucketResult mockResult = TokenBucketResult.builder()
                .allowed(true)
                .remainingTokens(19)
                .totalCapacity(20)
                .retryAfterMs(0L)
                .key(key)
                .build();

        when(tokenBucketAlgorithm.tryConsume(eq(key), eq(20.0), eq(1.0))).thenReturn(mockResult);

        RateLimitResponse response = tokenBucketService.isAllowed(identifier, endpoint);

        assertTrue(response.isAllowed());
        assertEquals(19, response.getRemainingRequests());
        assertEquals(60, response.getLimitPerMinute());
        assertEquals(20, response.getBurstSize());
        assertEquals(0L, response.getRetryAfterMs());
        assertEquals(identifier, response.getIdentifier());
        assertEquals(endpoint, response.getEndpoint());
    }

    @Test
    void isAllowed_overLimit_returnsRejected() {
        String identifier = "192.168.1.100";
        String endpoint = "url-create";
        String key = "tb:url-create:" + identifier;

        TokenBucketResult mockResult = TokenBucketResult.builder()
                .allowed(false)
                .remainingTokens(0)
                .totalCapacity(20)
                .retryAfterMs(500L)
                .key(key)
                .build();

        when(tokenBucketAlgorithm.tryConsume(eq(key), eq(20.0), eq(1.0))).thenReturn(mockResult);

        RateLimitResponse response = tokenBucketService.isAllowed(identifier, endpoint);

        assertFalse(response.isAllowed());
        assertEquals(0, response.getRemainingRequests());
        assertEquals(500L, response.getRetryAfterMs());
    }

    @Test
    void getRemainingRequests_returnsCorrectCount() {
        String identifier = "192.168.1.100";
        String endpoint = "url-create";
        String key = "tb:url-create:" + identifier;

        when(tokenBucketAlgorithm.getRemainingTokens(key)).thenReturn(14.5);

        int remaining = tokenBucketService.getRemainingRequests(identifier, endpoint);
        assertEquals(14, remaining);
    }

    @Test
    void resetLimit_clearsBucket() {
        String identifier = "192.168.1.100";
        String endpoint = "url-create";
        String key = "tb:url-create:" + identifier;

        tokenBucketService.resetLimit(identifier, endpoint);
        verify(tokenBucketAlgorithm, times(1)).resetBucket(key);
    }
}
