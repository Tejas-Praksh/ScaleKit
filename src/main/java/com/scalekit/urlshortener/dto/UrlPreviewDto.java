package com.scalekit.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Preview metadata extracted from a URL's OG (Open Graph) tags.
 *
 * <p>Scraped on demand by {@code UrlPreviewService} and cached in Redis
 * for 24 hours to avoid repeated HTTP calls for the same URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UrlPreviewDto {

    /** The URL that was scraped. */
    private String url;

    /** {@code og:title} or {@code <title>} fallback. */
    private String title;

    /** {@code og:description}. */
    private String description;

    /** {@code og:image} URL. */
    private String imageUrl;

    /** {@code og:site_name}. */
    private String siteName;

    /** URL of the favicon extracted from {@code <link rel="icon">}. */
    private String favicon;

    /**
     * Whether the target URL passed basic safety heuristics.
     * Defaults to {@code true}; set to {@code false} if scraping detects issues.
     */
    @Builder.Default
    private boolean isSafe = true;

    /** When the preview was scraped (for cache freshness display). */
    private Instant scrapedAt;
}
