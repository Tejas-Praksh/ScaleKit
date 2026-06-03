package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.dto.ThreatType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heuristical phishing detector using brand impersonation, homographs, and keyword checks.
 */
@Component
public class PhishingDetector {

    private static final Logger log = LoggerFactory.getLogger(PhishingDetector.class);

    private final Set<String> protectedBrands = ConcurrentHashMap.newKeySet();

    private static final List<String> SUSPICIOUS_PATH_KEYWORDS = Arrays.asList(
            "login", "signin", "verify", "confirm", "account", "password", "credential",
            "banking", "secure", "update", "alert", "suspended", "locked", "unusual"
    );

    @PostConstruct
    public void loadProtectedBrands() {
        // Default brand set
        Collections.addAll(protectedBrands,
                "paypal", "amazon", "google", "facebook", "apple", "microsoft", "netflix",
                "instagram", "twitter", "youtube", "linkedin", "github", "dropbox", "stripe", "coinbase"
        );

        // Load from classpath file if available
        try {
            ClassPathResource resource = new ClassPathResource("security/protected-brands.txt");
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        protectedBrands.add(line.toLowerCase());
                        count++;
                    }
                    log.info("Loaded {} protected brand names from protected-brands.txt", count);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load brand names from protected-brands.txt: {}", e.getMessage());
        }
    }

    /**
     * Runs phishing heuristic checks on the given URL.
     */
    public List<ThreatType> detectPhishing(String url, String domain) {
        List<ThreatType> threats = new ArrayList<>();
        if (url == null || domain == null) {
            return threats;
        }

        String lowerUrl = url.toLowerCase().trim();
        String lowerDomain = domain.toLowerCase().trim();

        // 1. Homograph Check
        if (isHomograph(lowerDomain)) {
            threats.add(ThreatType.PHISHING);
        }

        // 2. Brand Impersonation Check
        for (String brand : protectedBrands) {
            if (lowerUrl.contains(brand)) {
                // If URL contains brand name, check if the domain is legitimate for that brand
                if (!isLegitimateBrandDomain(lowerDomain, brand)) {
                    threats.add(ThreatType.PHISHING);
                    break;
                }
            }
        }

        // 3. Suspicious Keywords in Path Check
        if (hasSuspiciousPathKeywords(lowerUrl)) {
            threats.add(ThreatType.CREDENTIAL_HARVESTING);
        }

        // 4. Excessive Special Characters in Domain Check
        if (hasExcessiveSpecialChars(lowerDomain)) {
            threats.add(ThreatType.PHISHING);
        }

        // 5. Long Subdomain Chain Check
        if (hasExcessiveSubdomains(lowerDomain)) {
            threats.add(ThreatType.EXCESSIVE_SUBDOMAINS);
        }

        return threats;
    }

    private boolean isLegitimateBrandDomain(String domain, String brand) {
        String base = getBaseDomain(domain);
        return base.equals(brand);
    }

    private String getBaseDomain(String domain) {
        if (domain == null) {
            return "";
        }
        String[] parts = domain.split("\\.");
        if (parts.length < 2) {
            return domain;
        }
        int len = parts.length;
        String tld = parts[len - 1];
        String sld = parts[len - 2];
        // Heuristic for two-part TLDs (e.g., co.uk, com.br, org.za)
        if (tld.length() == 2 && sld.length() <= 3) {
            if (len >= 3) {
                return parts[len - 3];
            }
        }
        return sld;
    }

    private boolean isHomograph(String domain) {
        String base = getBaseDomain(domain);
        String normalized = base.replace("0", "o")
                .replace("1", "l")
                .replace("rn", "m");

        if (!base.equals(normalized) && protectedBrands.contains(normalized)) {
            log.warn("Detected homograph domain: {} normalized to protected brand: {}", domain, normalized);
            return true;
        }
        return false;
    }

    private boolean hasSuspiciousPathKeywords(String url) {
        try {
            String path = "";
            if (url.startsWith("http://") || url.startsWith("https://")) {
                path = new URI(url).getPath();
            } else {
                path = new URI("http://" + url).getPath();
            }
            if (path == null) {
                path = url;
            }

            int keywordCount = 0;
            for (String keyword : SUSPICIOUS_PATH_KEYWORDS) {
                if (path.contains(keyword)) {
                    keywordCount++;
                }
            }
            return keywordCount >= 3;
        } catch (Exception e) {
            // Substring fallback
            int slashIndex = url.indexOf("/", 8); // Skip proto
            if (slashIndex != -1) {
                String path = url.substring(slashIndex);
                int keywordCount = 0;
                for (String keyword : SUSPICIOUS_PATH_KEYWORDS) {
                    if (path.contains(keyword)) {
                        keywordCount++;
                    }
                }
                return keywordCount >= 3;
            }
            return false;
        }
    }

    private boolean hasExcessiveSpecialChars(String domain) {
        int count = 0;
        for (char c : domain.toCharArray()) {
            if (c == '@' || c == '%' || c == '-' || c == '_') {
                count++;
            }
        }
        return count > 3;
    }

    private boolean hasExcessiveSubdomains(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        int len = parts.length;
        String tld = parts[len - 1];
        String sld = parts[len - 2];
        int baseIndex = 2;
        // Adjust for country code double TLDs
        if (tld.length() == 2 && sld.length() <= 3) {
            baseIndex = 3;
        }

        int subdomainsCount = parts.length - baseIndex;
        return subdomainsCount > 3;
    }
}
