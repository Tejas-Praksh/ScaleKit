package com.scalekit.urlshortener.controller;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.util.IpUtil;
import com.scalekit.urlshortener.service.ClickTrackingService;
import com.scalekit.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller dedicated to rapid URL redirection.
 *
 * <p>Handles redirection off the main administrative API prefix
 * to maximize throughput and simplify routing rules.
 *
 * <p>Supports password-protected URLs via optional headers:
 * <ul>
 *   <li>{@code X-URL-Password} — plain-text password for password-protected URLs</li>
 *   <li>{@code X-Temp-Token} — a short-lived JWT obtained from {@code /verify-password}</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    private final UrlService urlService;
    private final ClickTrackingService clickTrackingService;

    /**
     * Resolves the short code to its destination, registers the event asynchronously,
     * and redirects the client with an HTTP 302 (Found) status code.
     *
     * <p>For password-protected URLs, reads {@code X-URL-Password} and {@code X-Temp-Token}
     * headers. If the URL is password-protected and no valid credential is present,
     * returns HTTP 401 with a JSON body.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<?> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        log.info("Redirect request for short code: {}", shortCode);

        // Extract optional password and temp token from headers
        String password = request.getHeader("X-URL-Password");
        String tempToken = request.getHeader("X-Temp-Token");

        // 1. Resolve destination URL (validates active, expiry, password protection)
        String originalUrl = urlService.resolveUrlWithPassword(shortCode, password, tempToken);

        // 2. Extract telemetry headers
        String ipAddress = IpUtil.extractClientIp(request);
        String referrer = request.getHeader(HttpHeaders.REFERER);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);

        // 3. Asynchronously record the event (zero latency penalty for redirection path)
        clickTrackingService.trackClick(shortCode, ipAddress, referrer, userAgent);

        // 4. Return redirection response
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, originalUrl)
                .build();
    }
}
