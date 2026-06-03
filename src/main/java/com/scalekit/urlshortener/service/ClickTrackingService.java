package com.scalekit.urlshortener.service;

/**
 * Service for non-blocking click tracking and analytics extraction.
 *
 * <p>Execution is performed asynchronously to prevent introducing
 * latency to the core user redirection path.
 */
public interface ClickTrackingService {

    /**
     * Records a click event, parses metadata asynchronously, updates analytics counters,
     * and compiles aggregated daily metrics.
     *
     * @param shortCode  the accessed short code
     * @param ipAddress  the client IP address
     * @param referrer   the HTTP referrer header
     * @param userAgent  the HTTP user agent string
     */
    void trackClick(String shortCode, String ipAddress, String referrer, String userAgent);
}
