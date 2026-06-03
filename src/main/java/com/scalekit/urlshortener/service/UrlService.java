package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.CreateUrlRequest;
import com.scalekit.urlshortener.dto.UpdateUrlRequest;
import com.scalekit.urlshortener.dto.UrlResponse;
import com.scalekit.urlshortener.dto.UrlStatsResponse;

/**
 * Service interface for URL shortening core operations.
 *
 * <p>Handles the creation of shortened URLs, high-performance lookup
 * with fallback mechanisms, and metadata fetching.
 */
public interface UrlService {

    /**
     * Creates a new shortened URL mapping.
     *
     * @param request validation request payload containing target URL and options
     * @return the detailed response of the created short URL
     */
    UrlResponse createUrl(CreateUrlRequest request);

    /**
     * Resolves a short code to its original destination URL.
     *
     * <p>Utilizes ultra-fast caching and handles soft-deletes and expirations.
     *
     * @param shortCode the base62 short code
     * @return the original target URL
     */
    String resolveUrl(String shortCode);

    /**
     * Resolves a short code to its original URL, enforcing password protection.
     *
     * <p>Checks (in order): temp token validity → password match → throws UrlPasswordException.
     *
     * @param shortCode      the base62 short code
     * @param password       plain-text password supplied by the client (may be null)
     * @param tempToken      a previously issued temp access token (may be null)
     * @return the original target URL
     */
    String resolveUrlWithPassword(String shortCode, String password, String tempToken);

    /**
     * Retrieves the URL metadata block by short code.
     *
     * @param shortCode the base62 short code
     * @return URL metadata details
     */
    UrlResponse getUrl(String shortCode);

    /**
     * Retrieves aggregated click and unique visitor statistics for a URL.
     *
     * @param shortCode the base62 short code
     * @return statistical summary DTO
     */
    UrlStatsResponse getUrlStats(String shortCode);

    /**
     * Partially updates a shortened URL's metadata.
     *
     * @param shortCode the base62 short code to update
     * @param request   fields to update; null fields are ignored
     * @return the updated URL response
     */
    UrlResponse updateUrl(String shortCode, UpdateUrlRequest request);

    /**
     * Deletes a short URL by marking it inactive (soft delete) and invalidates cache.
     *
     * @param shortCode the base62 short code
     */
    void deleteUrl(String shortCode);

    /**
     * Checks whether a short code or custom alias already exists.
     *
     * @param shortCode the code to check
     * @return {@code true} if a URL with this short code exists (active or inactive)
     */
    boolean existsByShortCode(String shortCode);
}
