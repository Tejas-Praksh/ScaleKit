package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.domain.Url;

import java.time.Instant;

/**
 * Helper service for URL expiry validation and date calculation.
 *
 * <p>Centralises all expiry logic so callers (service layer, redirect controller)
 * use a single, consistent check rather than inline {@code Instant} comparisons.
 */
public interface UrlExpiryService {

    /**
     * Validates that a URL has not expired.
     *
     * @param url the URL entity to check
     * @throws com.scalekit.common.exception.UrlExpiredException (HTTP 410) if expired
     */
    void validateExpiry(Url url);

    /**
     * Calculates an absolute expiry {@link Instant} for a relative duration.
     *
     * @param days number of days from now (1–365)
     * @return the computed {@link Instant}
     * @throws IllegalArgumentException if {@code days} is outside the 1–365 range
     */
    Instant calculateExpiryDate(int days);

    /**
     * Returns {@code true} if the URL has an expiry date that is already in the past.
     * A {@code null} expiry date means the URL never expires, so this returns {@code false}.
     *
     * @param url the URL entity
     * @return {@code true} if expired
     */
    boolean isExpired(Url url);
}
