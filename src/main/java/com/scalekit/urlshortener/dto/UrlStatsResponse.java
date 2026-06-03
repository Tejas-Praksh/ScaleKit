package com.scalekit.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Analytics summary for a shortened URL.
 *
 * <p>Available even for expired URLs so operators can audit
 * performance before deciding to renew a short code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UrlStatsResponse {

    private String shortCode;
    private String originalUrl;
    private long totalClicks;
    private long uniqueClicks;
    private Instant createdAt;
    private Instant lastAccessedAt;
    private boolean active;
    private boolean expired;
}
