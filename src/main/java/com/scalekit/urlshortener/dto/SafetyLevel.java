package com.scalekit.urlshortener.dto;

import lombok.Getter;

/**
 * Safety bands based on URL reputation scores.
 */
@Getter
public enum SafetyLevel {
    SAFE(70, 100, "URL appears safe"),
    WARNING(30, 69, "URL has suspicious characteristics"),
    DANGEROUS(0, 29, "URL is likely malicious"),
    BLOCKED(-1, -1, "URL is explicitly blocked");

    private final int minScore;
    private final int maxScore;
    private final String description;

    SafetyLevel(int minScore, int maxScore, String description) {
        this.minScore = minScore;
        this.maxScore = maxScore;
        this.description = description;
    }

    /**
     * Resolves SafetyLevel from reputation score.
     */
    public static SafetyLevel fromScore(int score) {
        if (score >= 70) {
            return SAFE;
        } else if (score >= 30) {
            return WARNING;
        } else if (score >= 0) {
            return DANGEROUS;
        } else {
            return BLOCKED;
        }
    }
}
