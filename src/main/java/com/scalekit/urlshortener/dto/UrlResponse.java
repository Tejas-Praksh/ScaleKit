package com.scalekit.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO for a shortened URL, returned by create and get endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UrlResponse {

    private String shortCode;

    /** Fully qualified short URL (e.g. http://localhost:8080/abc1234). */
    private String shortUrl;

    private String originalUrl;
    private String customAlias;
    private Instant createdAt;
    private Instant expiresAt;
    private Boolean active;
    private Long clickCount;
    private Long uniqueClickCount;
    private Instant lastAccessedAt;
    private String createdBy;
    private String title;
    private String description;

    /** True if the URL's expiresAt is in the past. */
    private Boolean expired;
}
