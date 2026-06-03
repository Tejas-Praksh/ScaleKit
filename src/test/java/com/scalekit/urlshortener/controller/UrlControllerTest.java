package com.scalekit.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.config.RequestInterceptor;
import com.scalekit.common.exception.ResourceNotFoundException;
import com.scalekit.common.exception.UrlException;
import com.scalekit.urlshortener.dto.CreateUrlRequest;
import com.scalekit.urlshortener.dto.UrlResponse;
import com.scalekit.urlshortener.dto.UrlStatsResponse;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.dto.SafetyLevel;
import com.scalekit.urlshortener.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = UrlController.class)
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UrlService urlService;

    @MockBean
    private UrlSafetyService urlSafetyService;

    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @MockBean
    private UrlBulkService urlBulkService;


    @MockBean
    private UrlPasswordService urlPasswordService;

    @MockBean
    private QrCodeService qrCodeService;

    @MockBean
    private UrlPreviewService urlPreviewService;

    @MockBean
    private AnalyticsQueryService analyticsQueryService;

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
    void createUrl_WithValidPayload_ShouldReturnCreated() throws Exception {
        // Arrange
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://deepmind.google")
                .customAlias("deepmind")
                .build();

        UrlResponse response = UrlResponse.builder()
                .shortCode("deepmind")
                .shortUrl("http://localhost:8080/deepmind")
                .originalUrl("https://deepmind.google")
                .customAlias("deepmind")
                .active(true)
                .clickCount(0L)
                .build();

        when(urlService.createUrl(any(CreateUrlRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("URL shortened successfully"))
                .andExpect(jsonPath("$.data.shortCode").value("deepmind"))
                .andExpect(jsonPath("$.data.originalUrl").value("https://deepmind.google"));

        verify(urlService, times(1)).createUrl(any(CreateUrlRequest.class));
    }

    @Test
    void createUrl_WithInvalidUrl_ShouldReturnBadRequest() throws Exception {
        // Arrange - invalid URL shape
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("not-a-valid-url")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(urlService);
    }

    @Test
    void getUrl_WhenShortCodeExists_ShouldReturnUrlResponse() throws Exception {
        // Arrange
        String shortCode = "abc1234";
        UrlResponse response = UrlResponse.builder()
                .shortCode(shortCode)
                .shortUrl("http://localhost:8080/" + shortCode)
                .originalUrl("https://example.com")
                .active(true)
                .build();

        when(urlService.getUrl(shortCode)).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/v1/urls/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortCode").value(shortCode))
                .andExpect(jsonPath("$.data.originalUrl").value("https://example.com"));
    }

    @Test
    void getUrl_WhenShortCodeDoesNotExist_ShouldReturnNotFound() throws Exception {
        // Arrange
        String shortCode = "nonexistent";
        when(urlService.getUrl(shortCode)).thenThrow(new ResourceNotFoundException("URL", shortCode));

        // Act & Assert
        mockMvc.perform(get("/api/v1/urls/{shortCode}", shortCode))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void getUrlStats_WhenShortCodeExists_ShouldReturnStats() throws Exception {
        // Arrange
        String shortCode = "abc1234";
        UrlStatsResponse response = UrlStatsResponse.builder()
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .totalClicks(105L)
                .uniqueClicks(62L)
                .createdAt(Instant.now())
                .active(true)
                .build();

        when(urlService.getUrlStats(shortCode)).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/v1/urls/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalClicks").value(105))
                .andExpect(jsonPath("$.data.uniqueClicks").value(62));
    }

    @Test
    void deleteUrl_WhenShortCodeExists_ShouldReturnSuccess() throws Exception {
        // Arrange
        String shortCode = "abc1234";
        doNothing().when(urlService).deleteUrl(shortCode);

        // Act & Assert
        mockMvc.perform(delete("/api/v1/urls/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("URL deleted successfully"));

        verify(urlService, times(1)).deleteUrl(shortCode);
    }

    @Test
    void safetyCheck_WithValidUrl_ShouldReturnResult() throws Exception {
        String testUrl = "http://legitimate.com";
        SafetyCheckResult result = SafetyCheckResult.builder()
                .url(testUrl)
                .isSafe(true)
                .reputationScore(100)
                .safetyLevel(SafetyLevel.SAFE)
                .build();

        when(urlSafetyService.checkUrl(testUrl)).thenReturn(result);

        mockMvc.perform(get("/api/v1/urls/safety-check")
                        .param("url", testUrl)
                        .header("X-Forwarded-For", "123.45.67.89"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.url").value(testUrl))
                .andExpect(jsonPath("$.data.safe").value(true));
    }

    @Test
    void safetyCheck_RateLimitExceeded_ShouldReturnTooManyRequests() throws Exception {
        String testUrl = "http://legitimate.com";
        SafetyCheckResult result = SafetyCheckResult.builder()
                .url(testUrl)
                .isSafe(true)
                .reputationScore(100)
                .safetyLevel(SafetyLevel.SAFE)
                .build();

        when(urlSafetyService.checkUrl(testUrl)).thenReturn(result);

        // Make 30 calls successfully
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/api/v1/urls/safety-check")
                            .param("url", testUrl)
                            .header("X-Forwarded-For", "111.111.111.111"))
                    .andExpect(status().isOk());
        }

        // The 31st call must return 429 Too Many Requests
        mockMvc.perform(get("/api/v1/urls/safety-check")
                        .param("url", testUrl)
                        .header("X-Forwarded-For", "111.111.111.111"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }
}

