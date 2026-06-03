package com.scalekit.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.ratelimiter.algorithm.RateLimitAlgorithm;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import com.scalekit.ratelimiter.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Verifies request interception, identifier extraction, endpoint mapping,
 * header injection, and 429 rejection behaviour.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private RateLimitFilter rateLimitFilter;

    @Mock private RateLimiterService rateLimiterService;
    @Mock private RateLimitRules rateLimitRules;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter(rateLimiterService, rateLimitRules, objectMapper);

        Map<String, RateLimitRules.EndpointRule> endpoints = new HashMap<>();
        RateLimitRules.EndpointRule ipRule = new RateLimitRules.EndpointRule();
        ipRule.setRequestsPerMinute(60);
        ipRule.setBurstSize(20);
        ipRule.setAlgorithm(RateLimitAlgorithm.TOKEN_BUCKET);
        ipRule.setIdentifierType("IP");
        ipRule.setEnabled(true);
        endpoints.put("url-create", ipRule);
        endpoints.put("api-global", ipRule);

        RateLimitRules.EndpointRule userRule = new RateLimitRules.EndpointRule();
        userRule.setRequestsPerMinute(100);
        userRule.setBurstSize(30);
        userRule.setAlgorithm(RateLimitAlgorithm.TOKEN_BUCKET);
        userRule.setIdentifierType("USER");
        userRule.setEnabled(true);
        endpoints.put("url-redirect", userRule);

        lenient().when(rateLimitRules.getEndpoints()).thenReturn(endpoints);
    }

    // ── Allowed requests ────────────────────────────────────────────────

    @Test
    void doFilter_allowed_callsChainAndSetsHeaders() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/urls");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        RateLimitResponse allowed = RateLimitResponse.builder()
                .allowed(true)
                .remainingRequests(19)
                .limitPerMinute(60)
                .burstSize(20)
                .retryAfterMs(0L)
                .identifier("192.168.1.1")
                .endpoint("url-create")
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .build();
        when(rateLimiterService.isAllowed("192.168.1.1", "url-create")).thenReturn(allowed);

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(response).setHeader("X-RateLimit-Limit", "60");
        verify(response).setHeader("X-RateLimit-Remaining", "19");
        verify(response).setHeader("X-RateLimit-Burst", "20");
    }

    // ── Rejected requests ───────────────────────────────────────────────

    @Test
    void doFilter_rejected_returns429WithBody() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/urls");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        RateLimitResponse rejected = RateLimitResponse.builder()
                .allowed(false)
                .remainingRequests(0)
                .limitPerMinute(60)
                .burstSize(20)
                .retryAfterMs(2000L)
                .identifier("10.0.0.1")
                .endpoint("url-create")
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .build();
        when(rateLimiterService.isAllowed("10.0.0.1", "url-create")).thenReturn(rejected);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "2");

        pw.flush();
        String body = sw.toString();
        assertTrue(body.contains("Too Many Requests"));
        assertTrue(body.contains("2000"));
    }

    // ── Excluded paths bypass filter ────────────────────────────────────

    @Test
    void doFilter_actuatorPath_skipsRateLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/health");

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verifyNoInteractions(rateLimiterService);
    }

    @Test
    void doFilter_swaggerPath_skipsRateLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/swagger-ui.html");

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verifyNoInteractions(rateLimiterService);
    }

    @Test
    void doFilter_benchmarkPath_skipsRateLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/benchmark/run");

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verifyNoInteractions(rateLimiterService);
    }

    // ── Fail-open on exception ──────────────────────────────────────────

    @Test
    void doFilter_serviceThrows_failsOpen() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/urls");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        when(rateLimiterService.isAllowed(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        rateLimitFilter.doFilter(request, response, filterChain);

        // Must still call the chain (fail-open)
        verify(filterChain, times(1)).doFilter(request, response);
    }

    // ── Endpoint key resolution ─────────────────────────────────────────

    @Test
    void resolveEndpointKey_postUrls_returnsUrlCreate() {
        when(request.getRequestURI()).thenReturn("/api/urls");
        when(request.getMethod()).thenReturn("POST");

        String key = rateLimitFilter.resolveEndpointKey(request);
        assertEquals("url-create", key);
    }

    @Test
    void resolveEndpointKey_postBulk_returnsUrlBulk() {
        when(request.getRequestURI()).thenReturn("/api/urls/bulk");
        when(request.getMethod()).thenReturn("POST");

        String key = rateLimitFilter.resolveEndpointKey(request);
        assertEquals("url-bulk", key);
    }

    @Test
    void resolveEndpointKey_getUrls_returnsUrlRedirect() {
        when(request.getRequestURI()).thenReturn("/api/urls");
        when(request.getMethod()).thenReturn("GET");

        String key = rateLimitFilter.resolveEndpointKey(request);
        assertEquals("url-redirect", key);
    }

    @Test
    void resolveEndpointKey_safetyCheck_returnsSafetyCheck() {
        when(request.getRequestURI()).thenReturn("/api/safety/check");
        when(request.getMethod()).thenReturn("POST");

        String key = rateLimitFilter.resolveEndpointKey(request);
        assertEquals("safety-check", key);
    }

    @Test
    void resolveEndpointKey_unknownPath_returnsApiGlobal() {
        when(request.getRequestURI()).thenReturn("/api/unknown");
        when(request.getMethod()).thenReturn("GET");

        String key = rateLimitFilter.resolveEndpointKey(request);
        assertEquals("api-global", key);
    }

    // ── Identifier extraction ───────────────────────────────────────────

    @Test
    void extractIdentifier_ipType_usesRemoteAddr() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        String id = rateLimitFilter.extractIdentifier(request, "url-create");
        assertEquals("192.168.1.100", id);
    }

    @Test
    void extractIdentifier_xForwardedFor_usesFirstIp() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 70.41.3.18");

        String id = rateLimitFilter.extractIdentifier(request, "url-create");
        assertEquals("203.0.113.5", id);
    }

    @Test
    void extractIdentifier_userType_usesUserHeader() {
        when(request.getHeader("X-User-Id")).thenReturn("user-42");

        String id = rateLimitFilter.extractIdentifier(request, "url-redirect");
        assertEquals("user-42", id);
    }

    @Test
    void extractIdentifier_userType_noHeader_fallsBackToIp() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        String id = rateLimitFilter.extractIdentifier(request, "url-redirect");
        assertEquals("10.0.0.5", id);
    }

    @Test
    void extractClientIp_xRealIp_header() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("172.16.0.1");

        String ip = rateLimitFilter.extractClientIp(request);
        assertEquals("172.16.0.1", ip);
    }
}
