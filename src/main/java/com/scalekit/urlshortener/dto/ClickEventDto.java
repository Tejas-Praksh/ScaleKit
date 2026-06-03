package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single click event with all parsed metadata.
 * Created at the point of click capture and enriched with geo/device
 * data before being persisted and aggregated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClickEventDto {

    private String shortCode;
    private String ipAddress;
    private String userAgent;
    private String referrer;
    private String acceptLanguage;
    private Instant clickedAt;

    // Parsed fields (enriched by processing pipeline)
    private String country;
    private String city;
    private String deviceType;
    private String browser;
    private String os;
    private boolean isUnique;
    private long responseTimeMs;
}
