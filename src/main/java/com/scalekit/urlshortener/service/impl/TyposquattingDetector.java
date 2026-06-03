package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.dto.ThreatType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Detector component checking for domain typosquatting using Levenshtein distance.
 */
@Component
public class TyposquattingDetector {

    private static final List<String> PROTECTED_DOMAINS = Arrays.asList(
            "google.com", "facebook.com", "amazon.com", "paypal.com", "apple.com", "microsoft.com",
            "netflix.com", "twitter.com", "instagram.com", "youtube.com", "linkedin.com", "github.com"
    );

    /**
     * Checks if the given domain is typosquatting one of the protected domains.
     */
    public Optional<ThreatType> detectTyposquatting(String domain) {
        if (domain == null) {
            return Optional.empty();
        }
        String lowerDomain = domain.toLowerCase().trim();

        String[] inputParts = extractRootAndTld(lowerDomain);
        String inputRoot = inputParts[0];
        String inputTld = inputParts[1];

        for (String protectedDomain : PROTECTED_DOMAINS) {
            String[] protectedParts = extractRootAndTld(protectedDomain);
            String protectedRoot = protectedParts[0];
            String protectedTld = protectedParts[1];

            // If it's the exact same domain, it's not typosquatting
            if (inputRoot.equals(protectedRoot) && inputTld.equals(protectedTld)) {
                continue;
            }

            int distance = levenshteinDistance(inputRoot, protectedRoot);
            if (distance == 1) {
                return Optional.of(ThreatType.TYPOSQUATTING);
            } else if (distance == 2 && inputTld.equalsIgnoreCase(protectedTld)) {
                return Optional.of(ThreatType.TYPOSQUATTING);
            }
        }

        return Optional.empty();
    }

    /**
     * Classic O(m*n) space and time dynamic programming Levenshtein distance implementation.
     */
    public int levenshteinDistance(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        int m = a.length();
        int n = b.length();

        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    int insertion = dp[i][j - 1] + 1;
                    int deletion = dp[i - 1][j] + 1;
                    int substitution = dp[i - 1][j - 1] + 1;
                    dp[i][j] = Math.min(Math.min(insertion, deletion), substitution);
                }
            }
        }

        return dp[m][n];
    }

    private String[] extractRootAndTld(String domain) {
        if (domain == null) {
            return new String[]{"", ""};
        }
        String[] parts = domain.split("\\.");
        if (parts.length < 2) {
            return new String[]{domain, ""};
        }
        int len = parts.length;
        String tld = parts[len - 1];
        String sld = parts[len - 2];

        // Multi-part TLD check (e.g. co.uk, com.br)
        if (tld.length() == 2 && sld.length() <= 3) {
            if (len >= 3) {
                return new String[]{parts[len - 3], sld + "." + tld};
            }
            return new String[]{sld, tld};
        }

        return new String[]{sld, tld};
    }
}
