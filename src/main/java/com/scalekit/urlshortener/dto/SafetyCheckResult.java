package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Result object returned by the URL safety scanner.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyCheckResult {
    private String url;
    private boolean isSafe;
    private int reputationScore;
    private SafetyLevel safetyLevel;
    private List<String> threats;
    private List<String> warnings;
    private String recommendation;
    private Instant checkedAt;
    private boolean fromCache;
}
