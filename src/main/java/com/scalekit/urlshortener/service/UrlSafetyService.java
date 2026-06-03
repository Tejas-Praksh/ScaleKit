package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.SafetyCheckResult;

/**
 * Service orchestrating safety check evaluation and scoring for URLs.
 */
public interface UrlSafetyService {

    /**
     * Inspects a URL and evaluates its safety reputation score and threat types.
     */
    SafetyCheckResult checkUrl(String url);
}
