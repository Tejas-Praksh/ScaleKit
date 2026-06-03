package com.scalekit.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.config.RequestInterceptor;
import com.scalekit.common.exception.ResourceNotFoundException;
import com.scalekit.common.exception.UrlExpiredException;
import com.scalekit.common.exception.UrlPasswordException;
import com.scalekit.urlshortener.dto.*;
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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = UrlController.class)
class UrlControllerAdvancedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean private UrlService urlService;
    @MockBean private UrlSafetyService urlSafetyService;
    @MockBean private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    @MockBean private UrlBulkService urlBulkService;
    @MockBean private UrlPasswordService urlPasswordService;
    @MockBean private QrCodeService qrCodeService;
    @MockBean private UrlPreviewService urlPreviewService;
    @MockBean private AnalyticsQueryService analyticsQueryService;
    @MockBean private RequestInterceptor requestInterceptor;
    @MockBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockBean private com.scalekit.ratelimiter.service.RateLimiterService rateLimiterService;
    @MockBean private com.scalekit.ratelimiter.config.RateLimitRules rateLimitRules;

    @BeforeEach
    void setUp() {
        when(requestInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    // ── POST /api/v1/urls — create valid ────────────────────────────────────

    @Test
    void postCreateUrl_valid_returns201() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://deepmind.google")
                .build();

        UrlResponse response = UrlResponse.builder()
                .shortCode("abc1234")
                .shortUrl("http://localhost:8080/abc1234")
                .originalUrl("https://deepmind.google")
                .active(true)
                .clickCount(0L)
                .build();

        when(urlService.createUrl(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortCode").value("abc1234"));
    }

    // ── POST /api/v1/urls — invalid URL ────────────────────────────────────

    @Test
    void postCreateUrl_invalidUrl_returns400() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("not-a-url")
                .build();

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(urlService);
    }

    // ── GET /api/v1/urls/{shortCode} — expired → 410 ─────────────────────

    @Test
    void getUrl_expired_returns410() throws Exception {
        when(urlService.getUrl("expired")).thenThrow(new UrlExpiredException("expired"));

        mockMvc.perform(get("/api/v1/urls/{shortCode}", "expired"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("URL_EXPIRED"));
    }

    // ── GET /api/v1/urls/{shortCode} — password required → 401 ───────────

    @Test
    void getUrl_passwordProtected_redirect401() throws Exception {
        when(urlService.getUrl("protected")).thenThrow(new UrlPasswordException());

        mockMvc.perform(get("/api/v1/urls/{shortCode}", "protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_REQUIRED"));
    }

    // ── POST /api/v1/urls/bulk — returns 200 ──────────────────────────────

    @Test
    void postBulkCreate_returns200() throws Exception {
        BulkCreateUrlRequest bulkRequest = BulkCreateUrlRequest.builder()
                .urls(List.of(
                        CreateUrlRequest.builder().originalUrl("https://one.com").build(),
                        CreateUrlRequest.builder().originalUrl("https://two.com").build()))
                .build();

        BulkCreateUrlResponse bulkResponse = BulkCreateUrlResponse.builder()
                .successful(List.of())
                .failed(List.of())
                .totalRequested(2)
                .totalSuccessful(2)
                .totalFailed(0)
                .processingTimeMs(50L)
                .build();

        when(urlBulkService.bulkCreate(any(), any()))
                .thenReturn(com.scalekit.common.dto.ApiResponse.success(bulkResponse, "Done", 50L));

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── GET /api/v1/urls/{shortCode}/qr — returns 200 with Base64 ─────────

    @Test
    void getQrCode_returns200WithBase64() throws Exception {
        String shortCode = "abc1234";

        UrlResponse urlResponse = UrlResponse.builder()
                .shortCode(shortCode)
                .shortUrl("http://localhost:8080/" + shortCode)
                .originalUrl("https://example.com")
                .active(true)
                .build();

        QrCodeResponse qrResponse = QrCodeResponse.builder()
                .shortCode(shortCode)
                .shortUrl("http://localhost:8080/" + shortCode)
                .qrCodeBase64("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                .size(200)
                .format("PNG")
                .generatedAt(Instant.now())
                .build();

        when(urlService.getUrl(shortCode)).thenReturn(urlResponse);
        when(qrCodeService.getOrGenerateQrCode(eq(shortCode), anyString(), eq(200))).thenReturn(qrResponse);

        mockMvc.perform(get("/api/v1/urls/{shortCode}/qr", shortCode)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortCode").value(shortCode))
                .andExpect(jsonPath("$.data.format").value("PNG"))
                .andExpect(jsonPath("$.data.qrCodeBase64").isNotEmpty());
    }

    // ── GET /api/v1/urls/{shortCode}/preview — returns 200 ────────────────

    @Test
    void getPreview_returns200() throws Exception {
        String shortCode = "abc1234";

        UrlResponse urlResponse = UrlResponse.builder()
                .shortCode(shortCode)
                .shortUrl("http://localhost:8080/" + shortCode)
                .originalUrl("https://example.com")
                .active(true)
                .build();

        UrlPreviewDto preview = UrlPreviewDto.builder()
                .url("https://example.com")
                .title("Example Domain")
                .description("This domain is for use in illustrative examples.")
                .isSafe(true)
                .scrapedAt(Instant.now())
                .build();

        when(urlService.getUrl(shortCode)).thenReturn(urlResponse);
        when(urlPreviewService.getPreview("https://example.com")).thenReturn(Optional.of(preview));

        mockMvc.perform(get("/api/v1/urls/{shortCode}/preview", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Example Domain"))
                .andExpect(jsonPath("$.data.safe").value(true));
    }

    // ── GET /api/v1/urls/{shortCode}/exists — returns Boolean ─────────────

    @Test
    void existsUrl_returns200WithBoolean() throws Exception {
        when(urlService.existsByShortCode("abc1234")).thenReturn(true);

        mockMvc.perform(get("/api/v1/urls/{shortCode}/exists", "abc1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(true));
    }

    // ── PUT /api/v1/urls/{shortCode} — update returns 200 ─────────────────

    @Test
    void putUpdateUrl_valid_returns200() throws Exception {
        UpdateUrlRequest updateRequest = UpdateUrlRequest.builder()
                .title("Updated Title")
                .build();

        UrlResponse updatedResponse = UrlResponse.builder()
                .shortCode("abc1234")
                .shortUrl("http://localhost:8080/abc1234")
                .originalUrl("https://example.com")
                .title("Updated Title")
                .active(true)
                .build();

        when(urlService.updateUrl(eq("abc1234"), any(UpdateUrlRequest.class))).thenReturn(updatedResponse);

        mockMvc.perform(put("/api/v1/urls/{shortCode}", "abc1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Updated Title"));
    }

    // ── POST /api/v1/urls/{shortCode}/verify-password — returns token ──────

    @Test
    void verifyPassword_correct_returnsToken() throws Exception {
        PasswordVerifyRequest pwdRequest = new PasswordVerifyRequest();
        pwdRequest.setPassword("secret");

        UrlResponse urlResponse = UrlResponse.builder()
                .shortCode("locked")
                .originalUrl("https://secret.com")
                .active(true)
                .build();

        when(urlService.resolveUrlWithPassword("locked", "secret", null))
                .thenReturn("https://secret.com");
        when(urlPasswordService.generateTempAccessToken("locked"))
                .thenReturn("mock.jwt.token");

        mockMvc.perform(post("/api/v1/urls/{shortCode}/verify-password", "locked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pwdRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("mock.jwt.token"));
    }

    // ── GET /api/v1/urls/{shortCode}/analytics — returns summary ─────────────

    @Test
    void getAnalytics_returns200WithSummary() throws Exception {
        String shortCode = "abc1234";
        AnalyticsSummaryDto summary = AnalyticsSummaryDto.builder()
                .shortCode(shortCode)
                .originalUrl("https://example.com")
                .totalClicks(100L)
                .uniqueClicks(80L)
                .build();

        when(analyticsQueryService.getSummary(shortCode)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/urls/{shortCode}/analytics", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortCode").value(shortCode))
                .andExpect(jsonPath("$.data.totalClicks").value(100))
                .andExpect(jsonPath("$.data.uniqueClicks").value(80));
    }

    // ── GET /api/v1/urls/{shortCode}/analytics/realtime — returns real-time stats ──────

    @Test
    void getRealTimeStats_returns200WithStats() throws Exception {
        String shortCode = "abc1234";
        RealTimeStatsDto stats = RealTimeStatsDto.builder()
                .shortCode(shortCode)
                .clicksLastMinute(5L)
                .clicksLastHour(50L)
                .totalClicksToday(150L)
                .activeVisitors(3L)
                .build();

        when(analyticsQueryService.getRealTimeStats(shortCode)).thenReturn(stats);

        mockMvc.perform(get("/api/v1/urls/{shortCode}/analytics/realtime", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortCode").value(shortCode))
                .andExpect(jsonPath("$.data.clicksLastMinute").value(5))
                .andExpect(jsonPath("$.data.clicksLastHour").value(50));
    }

    // ── GET /api/v1/urls/top — returns top URLs ────────────────────────────

    @Test
    void getTopUrls_returns200WithList() throws Exception {
        List<UrlResponse> topUrls = List.of(
                UrlResponse.builder().shortCode("abc1234").clickCount(100L).build(),
                UrlResponse.builder().shortCode("xyz5678").clickCount(50L).build()
        );

        when(analyticsQueryService.getTopUrls(10)).thenReturn(topUrls);

        mockMvc.perform(get("/api/v1/urls/top").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].shortCode").value("abc1234"))
                .andExpect(jsonPath("$.data[0].clickCount").value(100))
                .andExpect(jsonPath("$.data[1].shortCode").value("xyz5678"))
                .andExpect(jsonPath("$.data[1].clickCount").value(50));
    }
}
