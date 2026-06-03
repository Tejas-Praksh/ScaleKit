package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.SafetyCheckResult;

import java.util.Optional;

/**
 * Cache interface for safety check results.
 */
public interface UrlReputationCache {

    /**
     * Caches a safety check result for a URL.
     */
    void cacheResult(String url, SafetyCheckResult result);

    /**
     * Retrieves a cached safety check result for a URL if present.
     */
    Optional<SafetyCheckResult> getCachedResult(String url);
}
