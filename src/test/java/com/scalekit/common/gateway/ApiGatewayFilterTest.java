package com.scalekit.common.gateway;

import com.scalekit.common.constants.SystemConstants;
import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.util.CorrelationIdUtil;
import com.scalekit.ratelimiter.service.RateLimiterService;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiGatewayFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RouteRegistry routeRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockBean
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        // Register default routes or clear
        // We will make sure the rate limiter defaults to allowed unless specified
        RateLimitResponse allowedResponse = RateLimitResponse.builder()
                .allowed(true)
                .remainingRequests(99)
                .limitPerMinute(100)
                .build();
        when(rateLimiterService.isAllowed(anyString(), anyString())).thenReturn(allowedResponse);
    }

    @Test
    void filter_addsCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/urls/test"))
                .andExpect(header().exists(SystemConstants.CORRELATION_ID_HEADER))
                .andExpect(header().exists("X-Response-Time"))
                .andExpect(status().isNotFound()); // NotFound since the shortcode doesn't exist
    }

    @Test
    void filter_existingCorrelationId_preserves() throws Exception {
        String testId = "custom-correlation-123";
        mockMvc.perform(get("/api/v1/urls/test")
                        .header(SystemConstants.CORRELATION_ID_HEADER, testId))
                .andExpect(header().string(SystemConstants.CORRELATION_ID_HEADER, testId))
                .andExpect(status().isNotFound());
    }

    @Test
    void filter_rateLimited_returns429() throws Exception {
        // Define a route to test rate limiting
        RouteRegistry.RouteDefinition routeDef = RouteRegistry.RouteDefinition.builder()
                .pattern("/api/v1/urls/**")
                .name("url-shortener")
                .rateLimited(true)
                .rateLimit(1)
                .version("v1")
                .build();
        routeRegistry.register(routeDef);

        RateLimitResponse blockedResponse = RateLimitResponse.builder()
                .allowed(false)
                .remainingRequests(0)
                .limitPerMinute(1)
                .retryAfterMs(5000L)
                .build();
        when(rateLimiterService.isAllowed(anyString(), Mockito.eq("url-shortener"))).thenReturn(blockedResponse);

        mockMvc.perform(get("/api/v1/urls/test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void filter_addsResponseTimeHeader() throws Exception {
        mockMvc.perform(get("/api/v1/urls/test"))
                .andExpect(header().exists("X-Response-Time"));
    }

    @Test
    void filter_clearsmdcAfterRequest() throws Exception {
        mockMvc.perform(get("/api/v1/urls/test"));
        assertNull(CorrelationIdUtil.get());
        assertNull(MDC.get("username"));
    }

    @Test
    void filter_unsupportedVersion_returns400() throws Exception {
        mockMvc.perform(get("/api/v5/urls/test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("API_VERSION_NOT_SUPPORTED"));
    }

    @Test
    void filter_requiresAuth_unauthorized() throws Exception {
        RouteRegistry.RouteDefinition authRoute = RouteRegistry.RouteDefinition.builder()
                .pattern("/api/v1/admin/**")
                .name("admin")
                .requiresAuth(true)
                .version("v1")
                .build();
        routeRegistry.register(authRoute);

        // No authorization header
        mockMvc.perform(get("/api/v1/admin/blacklist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void filter_requiresAuth_adminValidate_forbidden() throws Exception {
        // Invalid token
        mockMvc.perform(get("/api/v1/admin/blacklist")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void filter_requiresAuth_adminValidate_success() throws Exception {
        // Valid token
        mockMvc.perform(get("/api/v1/admin/blacklist")
                        .header("Authorization", "Bearer admin-token"))
                // Expect 200 since blacklist GET works out of the box in AdminController
                .andExpect(status().isOk());
    }

    @Test
    void filter_circuitBreakerOpen_returns503() throws Exception {
        RouteRegistry.RouteDefinition cbRoute = RouteRegistry.RouteDefinition.builder()
                .pattern("/api/v1/locks/**")
                .name("locking")
                .version("v1")
                .build();
        routeRegistry.register(cbRoute);

        // Force open circuit breaker
        for (int i = 0; i < 5; i++) {
            circuitBreakerRegistry.recordFailure("locking");
        }

        mockMvc.perform(get("/api/v1/locks/test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CIRCUIT_BREAKER_OPEN"));
    }
}
