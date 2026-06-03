package com.scalekit.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.ratelimiter.config.RateLimitRules;
import com.scalekit.ratelimiter.dto.RateLimitResponse;
import com.scalekit.ratelimiter.service.RateLimiterService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Jakarta Servlet Filter that enforces token bucket rate limiting on all incoming requests.
 *
 * <p>Intercepts requests before they reach controllers, extracts the client identifier
 * (IP or user), resolves the matching endpoint rule, and delegates to
 * {@link TokenBucketService} for the rate check. On rejection, responds with
 * {@code 429 Too Many Requests} including standard rate limit headers.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *     <li>Ordered with highest precedence so rate limiting is the first cross-cutting concern.</li>
 *     <li>Uses fail-open: if the service itself fails, the request is allowed through.</li>
 *     <li>Writes standard {@code X-RateLimit-*} headers on every response, not just 429s.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter implements Filter {

    private final RateLimiterService rateLimiterService;
    private final RateLimitRules rateLimitRules;
    private final ObjectMapper objectMapper;

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("RateLimitFilter initialized");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getRequestURI();

        // Skip actuator, swagger, and static resource paths
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        String endpointKey = resolveEndpointKey(httpRequest);
        String identifier = extractIdentifier(httpRequest, endpointKey);

        try {
            RateLimitResponse result = rateLimiterService.isAllowed(identifier, endpointKey);

            // Always write rate limit headers so clients can track their budget
            addRateLimitHeaders(httpResponse, result);

            if (!result.isAllowed()) {
                log.warn("Rate limit exceeded for identifier={} endpoint={} retryAfterMs={}",
                        identifier, endpointKey, result.getRetryAfterMs());

                httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                httpResponse.getWriter().write(objectMapper.writeValueAsString(Map.of(
                        "error", "Too Many Requests",
                        "message", "Rate limit exceeded. Please retry after " + result.getRetryAfterMs() + "ms",
                        "retryAfterMs", result.getRetryAfterMs(),
                        "limit", result.getLimitPerMinute(),
                        "remaining", result.getRemainingRequests(),
                        "timestamp", Instant.now().toString()
                )));
                return;
            }

            chain.doFilter(request, response);

        } catch (Exception e) {
            // Fail-open: if rate limiter throws, allow the request through
            log.error("Rate limit filter error for path={}. Failing open.", path, e);
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
        log.info("RateLimitFilter destroyed");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Maps the incoming request path + method to a configured endpoint key.
     */
    String resolveEndpointKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/urls") && "POST".equalsIgnoreCase(method)) {
            if (path.contains("/bulk")) {
                return "url-bulk";
            }
            return "url-create";
        }
        if (path.startsWith("/api/urls") && "GET".equalsIgnoreCase(method)) {
            return "url-redirect";
        }
        if (path.matches("/[a-zA-Z0-9]{3,20}") && "GET".equalsIgnoreCase(method)) {
            return "url-redirect";
        }
        if (path.startsWith("/api/safety")) {
            return "safety-check";
        }

        return "api-global";
    }

    /**
     * Extracts the rate-limiting identifier from the request based on the endpoint rule.
     */
    String extractIdentifier(HttpServletRequest request, String endpointKey) {
        if (rateLimitRules.getEndpoints() != null) {
            RateLimitRules.EndpointRule rule = rateLimitRules.getEndpoints().get(endpointKey);
            if (rule != null && "USER".equalsIgnoreCase(rule.getIdentifierType())) {
                String userId = request.getHeader("X-User-Id");
                if (userId != null && !userId.isBlank()) {
                    return userId;
                }
            }
            if (rule != null && "API_KEY".equalsIgnoreCase(rule.getIdentifierType())) {
                String apiKey = request.getHeader("X-Api-Key");
                if (apiKey != null && !apiKey.isBlank()) {
                    return apiKey;
                }
            }
        }

        // Default: use client IP
        return extractClientIp(request);
    }

    /**
     * Extracts the real client IP, honoring proxy headers.
     */
    String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * Adds standard rate limit response headers.
     */
    private void addRateLimitHeaders(HttpServletResponse response, RateLimitResponse result) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.getLimitPerMinute()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.getRemainingRequests()));
        response.setHeader("X-RateLimit-Burst", String.valueOf(result.getBurstSize()));
        if (result.getRetryAfterMs() > 0) {
            // Retry-After is in seconds per RFC 7231 §7.1.3
            response.setHeader("Retry-After",
                    String.valueOf(Math.max(1, result.getRetryAfterMs() / 1000)));
        }
    }

    /**
     * Checks if the path should be excluded from rate limiting.
     */
    private boolean isExcluded(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/swagger")
                || path.startsWith("/api-docs")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/favicon")
                || path.equals("/health")
                || path.startsWith("/api/ratelimit")
                || path.startsWith("/api/benchmark");
    }
}
