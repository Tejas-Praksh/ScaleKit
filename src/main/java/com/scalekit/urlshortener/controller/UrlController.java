package com.scalekit.urlshortener.controller;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.util.PerformanceUtil;
import com.scalekit.common.util.IpUtil;
import com.scalekit.common.exception.RateLimitExceededException;
import com.scalekit.urlshortener.dto.*;
import com.scalekit.urlshortener.service.*;
import jakarta.validation.Valid;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller managing URL shortener administrative operations,
 * advanced features (bulk, password, QR, preview), and statistics.
 */
@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private static final Logger log = LoggerFactory.getLogger(UrlController.class);

    private final UrlService urlService;
    private final UrlBulkService urlBulkService;
    private final UrlPasswordService urlPasswordService;
    private final QrCodeService qrCodeService;
    private final UrlPreviewService urlPreviewService;
    private final AnalyticsQueryService analyticsQueryService;
    private final UrlSafetyService urlSafetyService;
    private final StringRedisTemplate stringRedisTemplate;

    private final ConcurrentHashMap<String, java.util.Queue<Long>> localRateLimits = new ConcurrentHashMap<>();

    public UrlController(
            UrlService urlService,
            UrlBulkService urlBulkService,
            UrlPasswordService urlPasswordService,
            QrCodeService qrCodeService,
            UrlPreviewService urlPreviewService,
            AnalyticsQueryService analyticsQueryService,
            UrlSafetyService urlSafetyService,
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate) {
        this.urlService = urlService;
        this.urlBulkService = urlBulkService;
        this.urlPasswordService = urlPasswordService;
        this.qrCodeService = qrCodeService;
        this.urlPreviewService = urlPreviewService;
        this.analyticsQueryService = analyticsQueryService;
        this.urlSafetyService = urlSafetyService;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    // ── Core CRUD ──────────────────────────────────────────────────────────

    /**
     * Creates a new shortened URL mapping.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<UrlResponse>> createUrl(@Valid @RequestBody CreateUrlRequest request) {
        log.info("REST request to shorten URL: {}", request.getOriginalUrl());
        long start = PerformanceUtil.startTimer();
        UrlResponse response = urlService.createUrl(request);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "URL shortened successfully", elapsed));
    }

    /**
     * Retrieves the metadata of a shortened URL by its short code.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<UrlResponse>> getUrl(@PathVariable String shortCode) {
        log.info("REST request to get URL details for: {}", shortCode);
        long start = PerformanceUtil.startTimer();
        UrlResponse response = urlService.getUrl(shortCode);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(response, "URL details retrieved successfully", elapsed));
    }

    /**
     * Partially updates a shortened URL's metadata.
     */
    @PutMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<UrlResponse>> updateUrl(
            @PathVariable String shortCode,
            @Valid @RequestBody UpdateUrlRequest request) {
        log.info("REST request to update URL: {}", shortCode);
        long start = PerformanceUtil.startTimer();
        UrlResponse response = urlService.updateUrl(shortCode, request);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(response, "URL updated successfully", elapsed));
    }

    /**
     * Retrieves the statistics of a shortened URL by its short code.
     */
    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<ApiResponse<UrlStatsResponse>> getUrlStats(@PathVariable String shortCode) {
        log.info("REST request to get statistics for: {}", shortCode);
        long start = PerformanceUtil.startTimer();
        UrlStatsResponse response = urlService.getUrlStats(shortCode);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(response, "URL statistics retrieved successfully", elapsed));
    }

    /**
     * Deletes a shortened URL mapping (soft delete).
     */
    @DeleteMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<Void>> deleteUrl(@PathVariable String shortCode) {
        log.info("REST request to delete short URL: {}", shortCode);
        long start = PerformanceUtil.startTimer();
        urlService.deleteUrl(shortCode);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(null, "URL deleted successfully", elapsed));
    }

    /**
     * Checks whether a short code already exists.
     */
    @GetMapping("/{shortCode}/exists")
    public ResponseEntity<ApiResponse<Boolean>> existsUrl(@PathVariable String shortCode) {
        boolean exists = urlService.existsByShortCode(shortCode);
        return ResponseEntity.ok(ApiResponse.success(exists,
                exists ? "Short code exists" : "Short code not found"));
    }

    // ── Bulk ───────────────────────────────────────────────────────────────

    /**
     * Creates multiple shortened URLs in a single request (max 100).
     */
    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<BulkCreateUrlResponse>> bulkCreate(
            @Valid @RequestBody BulkCreateUrlRequest request,
            @RequestHeader(value = "X-Created-By", required = false) String createdBy) {
        log.info("REST bulk URL creation request: {} URLs", request.getUrls().size());
        ApiResponse<BulkCreateUrlResponse> response = urlBulkService.bulkCreate(request, createdBy);
        return ResponseEntity.ok(response);
    }

    // ── Password ───────────────────────────────────────────────────────────

    /**
     * Verifies a password for a protected URL and returns a temporary access token.
     */
    @PostMapping("/{shortCode}/verify-password")
    public ResponseEntity<ApiResponse<String>> verifyPassword(
            @PathVariable String shortCode,
            @Valid @RequestBody PasswordVerifyRequest request) {
        log.info("REST password verification for: {}", shortCode);

        // Resolve URL to verify it exists and is password protected.
        // resolveUrlWithPassword validates the password and returns the original URL.
        urlService.resolveUrlWithPassword(shortCode, request.getPassword(), null);

        // Generate temp token for the client to use in subsequent redirects
        String token = urlPasswordService.generateTempAccessToken(shortCode);

        return ResponseEntity.ok(ApiResponse.success(token, "Password verified. Use the token for redirect access."));
    }

    // ── QR Code ────────────────────────────────────────────────────────────

    /**
     * Generates or retrieves a cached QR code for the short URL.
     */
    @GetMapping("/{shortCode}/qr")
    public ResponseEntity<ApiResponse<QrCodeResponse>> getQrCode(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "200") @Min(200) @Max(1000) int size) {
        log.info("REST QR code request for: {} size={}", shortCode, size);
        long start = PerformanceUtil.startTimer();

        // Get URL to build the full short URL string
        UrlResponse urlResponse = urlService.getUrl(shortCode);
        QrCodeResponse qrResponse = qrCodeService.getOrGenerateQrCode(shortCode, urlResponse.getShortUrl(), size);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(qrResponse, "QR code generated successfully", elapsed));
    }

    // ── Preview ────────────────────────────────────────────────────────────

    /**
     * Retrieves Open Graph preview metadata for the original URL.
     */
    @GetMapping("/{shortCode}/preview")
    public ResponseEntity<ApiResponse<UrlPreviewDto>> getPreview(@PathVariable String shortCode) {
        log.info("REST URL preview request for: {}", shortCode);
        long start = PerformanceUtil.startTimer();

        UrlResponse urlResponse = urlService.getUrl(shortCode);
        UrlPreviewDto preview = urlPreviewService.getPreview(urlResponse.getOriginalUrl())
                .orElse(UrlPreviewDto.builder()
                        .url(urlResponse.getOriginalUrl())
                        .title(urlResponse.getTitle())
                        .isSafe(true)
                        .build());
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(preview, "URL preview retrieved", elapsed));
    }

    // ── Analytics ──────────────────────────────────────────────────────────

    /**
     * Retrieves the analytics summary for a shortened URL.
     */
    @GetMapping("/{shortCode}/analytics")
    public ResponseEntity<ApiResponse<AnalyticsSummaryDto>> getUrlAnalytics(@PathVariable String shortCode) {
        log.info("REST request to get analytics summary for: {}", shortCode);
        long start = PerformanceUtil.startTimer();
        AnalyticsSummaryDto response = analyticsQueryService.getSummary(shortCode);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(response, "URL analytics summary retrieved successfully", elapsed));
    }

    /**
     * Retrieves real-time click stats for a shortened URL directly from Redis.
     */
    @GetMapping("/{shortCode}/analytics/realtime")
    public ResponseEntity<ApiResponse<RealTimeStatsDto>> getUrlRealTimeStats(@PathVariable String shortCode) {
        log.info("REST request to get real-time stats for: {}", shortCode);
        long start = PerformanceUtil.startTimer();
        RealTimeStatsDto response = analyticsQueryService.getRealTimeStats(shortCode);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(response, "URL real-time statistics retrieved successfully", elapsed));
    }

    /**
     * Retrieves the top performing URLs by click counts.
     */
    @GetMapping("/top")
    public ResponseEntity<ApiResponse<List<UrlResponse>>> getTopUrls(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        log.info("REST request to get top URLs with limit: {}", limit);
        long start = PerformanceUtil.startTimer();
        List<UrlResponse> response = analyticsQueryService.getTopUrls(limit);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(response, "Top performing URLs retrieved successfully", elapsed));
    }

    // ── URL Safety Checker ──────────────────────────────────────────────────

    /**
     * Inspects a URL and evaluates its safety reputation score and threat types.
     * Limits requests to 30 checks per minute per IP address.
     */
    @GetMapping("/safety-check")
    public ResponseEntity<ApiResponse<SafetyCheckResult>> safetyCheck(
            @RequestParam String url,
            jakarta.servlet.http.HttpServletRequest request) {
        log.info("REST URL safety check request for: {}", url);
        long start = PerformanceUtil.startTimer();

        String ip = IpUtil.extractClientIp(request);
        checkRateLimit(ip);

        SafetyCheckResult result = urlSafetyService.checkUrl(url);
        long elapsed = PerformanceUtil.elapsedMs(start);

        return ResponseEntity.ok(ApiResponse.success(result, "URL safety check completed successfully", elapsed));
    }

    private void checkRateLimit(String ip) {
        String key = "safety:ratelimit:" + ip;
        long now = System.currentTimeMillis();
        long windowStart = now - 60000;

        if (stringRedisTemplate != null && stringRedisTemplate.opsForZSet() != null) {
            try {
                stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
                stringRedisTemplate.opsForZSet().add(key, String.valueOf(now), now);
                stringRedisTemplate.expire(key, Duration.ofMinutes(1));
                Long count = stringRedisTemplate.opsForZSet().zCard(key);
                if (count != null && count > 30) {
                    throw new RateLimitExceededException(ip, 60);
                }
                return;
            } catch (Exception e) {
                if (e instanceof RateLimitExceededException) {
                    throw (RateLimitExceededException) e;
                }
                log.warn("Redis rate limiter failed for ip: {}, falling back to local map: {}", ip, e.getMessage());
            }
        }

        // Fallback to local map sliding window
        java.util.Queue<Long> timestamps = localRateLimits.computeIfAbsent(ip, k -> new ConcurrentLinkedQueue<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peek() < windowStart) {
                timestamps.poll();
            }
            if (timestamps.size() >= 30) {
                throw new RateLimitExceededException(ip, 60);
            }
            timestamps.add(now);
        }
    }
}

