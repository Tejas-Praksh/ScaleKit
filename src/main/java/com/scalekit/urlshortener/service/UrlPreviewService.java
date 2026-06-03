package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.UrlPreviewDto;

import java.util.Optional;

/**
 * Service for fetching and caching Open Graph metadata from URLs.
 *
 * <p>Scraped previews are cached in Redis for 24 hours to reduce
 * outbound HTTP calls and improve response time for repeated lookups.
 */
public interface UrlPreviewService {

    /**
     * Retrieves the Open Graph preview for a URL.
     *
     * <p>The result is cached on first fetch and returned from cache on
     * subsequent calls. If scraping fails or the URL is not HTML,
     * returns {@link Optional#empty()}.
     *
     * @param url the URL to scrape and preview
     * @return an {@link Optional} containing the preview, or empty if unavailable
     */
    Optional<UrlPreviewDto> getPreview(String url);
}
