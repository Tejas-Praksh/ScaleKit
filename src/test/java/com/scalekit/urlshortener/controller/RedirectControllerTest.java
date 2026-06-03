package com.scalekit.urlshortener.controller;

import com.scalekit.common.config.RequestInterceptor;
import com.scalekit.common.exception.ResourceNotFoundException;
import com.scalekit.common.exception.UrlExpiredException;
import com.scalekit.common.exception.UrlPasswordException;
import com.scalekit.urlshortener.service.ClickTrackingService;
import com.scalekit.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RedirectController.class)
class RedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlService urlService;

    @MockBean
    private ClickTrackingService clickTrackingService;

    @MockBean
    private RequestInterceptor requestInterceptor;

    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockBean
    private com.scalekit.ratelimiter.service.RateLimiterService rateLimiterService;

    @MockBean
    private com.scalekit.ratelimiter.config.RateLimitRules rateLimitRules;

    @BeforeEach
    void setUp() {
        // Mock interceptor preHandle to allow request through
        when(requestInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void redirect_WithValidShortCode_ShouldReturn302AndTriggerAsyncTracking() throws Exception {
        // Arrange
        String shortCode = "abc1234";
        String targetUrl = "https://deepmind.google/technologies/gemini/";

        when(urlService.resolveUrlWithPassword(eq(shortCode), any(), any())).thenReturn(targetUrl);
        doNothing().when(clickTrackingService).trackClick(eq(shortCode), any(), any(), any());

        // Act & Assert
        mockMvc.perform(get("/{shortCode}", shortCode)
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                        .header(HttpHeaders.REFERER, "https://github.com"))
                .andExpect(status().isFound()) // 302 Found
                .andExpect(header().string(HttpHeaders.LOCATION, targetUrl));

        // Verify async tracking was triggered with appropriate details
        verify(clickTrackingService, times(1))
                .trackClick(eq(shortCode), any(), eq("https://github.com"), eq("Mozilla/5.0"));
    }

    @Test
    void redirect_WithExpiredShortCode_ShouldReturn410Gone() throws Exception {
        // Arrange
        String shortCode = "expired";
        when(urlService.resolveUrlWithPassword(eq(shortCode), any(), any()))
                .thenThrow(new UrlExpiredException(shortCode));

        // Act & Assert
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone()) // 410 Gone
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("URL_EXPIRED"));

        // Verify tracking was not triggered
        verifyNoInteractions(clickTrackingService);
    }

    @Test
    void redirect_WithDeletedShortCode_ShouldReturn404NotFound() throws Exception {
        // Arrange
        String shortCode = "deleted";
        when(urlService.resolveUrlWithPassword(eq(shortCode), any(), any()))
                .thenThrow(new ResourceNotFoundException("URL", shortCode));

        // Act & Assert
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isNotFound()) // 404 Not Found
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        // Verify tracking was not triggered
        verifyNoInteractions(clickTrackingService);
    }

    @Test
    void redirect_WithPasswordProtectedUrl_ShouldReturn401() throws Exception {
        // Arrange
        String shortCode = "locked";
        when(urlService.resolveUrlWithPassword(eq(shortCode), isNull(), isNull()))
                .thenThrow(new UrlPasswordException());

        // Act & Assert
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_REQUIRED"));

        verifyNoInteractions(clickTrackingService);
    }

    @Test
    void redirect_WithCorrectTempToken_ShouldReturn302() throws Exception {
        // Arrange
        String shortCode = "locked";
        String targetUrl = "https://secret.com";
        String tempToken = "valid.jwt.token";

        when(urlService.resolveUrlWithPassword(eq(shortCode), isNull(), eq(tempToken)))
                .thenReturn(targetUrl);

        // Act & Assert
        mockMvc.perform(get("/{shortCode}", shortCode)
                        .header("X-Temp-Token", tempToken))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, targetUrl));
    }
}
