package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.util.IpUtil;
import com.scalekit.urlshortener.dto.ThreatType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checker component checking for suspicious URL formats, protocols, path extensions, length, redirects, and free hosting.
 */
@Component
public class SuspiciousPatternChecker {

    private static final List<String> MISLEADING_BRANDS = Arrays.asList(
            "paypal.com", "google.com", "amazon.com", "facebook.com", "apple.com", "microsoft.com", "netflix.com"
    );

    private static final List<String> FREE_HOSTS = Arrays.asList(
            "weebly.com", "wix.com", "blogspot.com"
    );

    /**
     * Checks a URL for multiple suspicious pattern heuristics.
     */
    public List<ThreatType> checkPatterns(String url) {
        List<ThreatType> threats = new ArrayList<>();
        if (url == null) {
            return threats;
        }

        String lowerUrl = url.toLowerCase().trim();

        // 1. Data and JavaScript Protocol checks (instantly blocked)
        if (lowerUrl.startsWith("javascript:") || lowerUrl.startsWith("data:")) {
            threats.add(ThreatType.BLACKLISTED_DOMAIN);
            return threats; // Stop check immediately since it is fully blocked
        }

        // 2. Length check
        if (url.length() > 2000) {
            threats.add(ThreatType.SUSPICIOUS_PATH);
        }

        // Parse URI parts
        String host = null;
        String path = null;
        String query = null;
        try {
            URI uri;
            if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
                uri = new URI(url);
            } else {
                uri = new URI("http://" + url);
            }
            host = uri.getHost();
            path = uri.getPath();
            query = uri.getQuery();
        } catch (Exception e) {
            // Basic parsing fallback
            int protoIndex = lowerUrl.indexOf("://");
            int hostStart = (protoIndex != -1) ? protoIndex + 3 : 0;
            int slashIndex = lowerUrl.indexOf("/", hostStart);
            if (slashIndex != -1) {
                host = lowerUrl.substring(hostStart, slashIndex);
                int qIndex = lowerUrl.indexOf("?", slashIndex);
                if (qIndex != -1) {
                    path = lowerUrl.substring(slashIndex, qIndex);
                    query = lowerUrl.substring(qIndex + 1);
                } else {
                    path = lowerUrl.substring(slashIndex);
                }
            } else {
                host = lowerUrl.substring(hostStart);
            }
        }

        if (host != null) {
            String lowerHost = host.toLowerCase().trim();

            // 3. Raw IP check
            if (isRawIpAddress(lowerHost)) {
                threats.add(ThreatType.IP_ADDRESS_URL);
            }

            // 4. Misleading subdomains
            for (String misleadingBrand : MISLEADING_BRANDS) {
                if (lowerHost.contains(misleadingBrand)) {
                    String baseBrand = misleadingBrand.split("\\.")[0];
                    if (!getBaseDomain(lowerHost).equals(baseBrand)) {
                        threats.add(ThreatType.PHISHING);
                        break;
                    }
                }
            }

            // 5. Free hosting check
            for (String freeHost : FREE_HOSTS) {
                if (lowerHost.equals(freeHost) || lowerHost.endsWith("." + freeHost)) {
                    threats.add(ThreatType.FREE_HOSTING);
                    break;
                }
            }
        }

        // 6. Suspicious path extensions (Malware download context)
        if (path != null) {
            String lowerPath = path.toLowerCase();
            if (lowerPath.endsWith(".exe") || lowerPath.endsWith(".zip") || lowerPath.endsWith(".rar") ||
                lowerPath.endsWith(".bat") || lowerPath.endsWith(".cmd") || lowerPath.endsWith(".vbs") ||
                lowerPath.endsWith(".js")) {
                threats.add(ThreatType.MALWARE);
            }
        }

        // 7. Multiple Redirects in query
        if (query != null) {
            String lowerQuery = query.toLowerCase();
            if (lowerQuery.contains("http://") || lowerQuery.contains("https://") || lowerQuery.contains("http%3a%2f%2f") || lowerQuery.contains("https%3a%2f%2f")) {
                threats.add(ThreatType.SUSPICIOUS_REDIRECT);
            }
        }

        return threats;
    }

    private boolean isRawIpAddress(String host) {
        String cleanHost = host;
        if (host.startsWith("[") && host.endsWith("]")) {
            cleanHost = host.substring(1, host.length() - 1);
        }
        if (cleanHost.contains(":")) {
            return true; // DNS hosts cannot contain colons, exclusive to IPv6
        }
        return IpUtil.isValidIp(cleanHost);
    }

    private String getBaseDomain(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length < 2) {
            return domain;
        }
        int len = parts.length;
        String tld = parts[len - 1];
        String sld = parts[len - 2];
        if (tld.length() == 2 && sld.length() <= 3) {
            if (len >= 3) {
                return parts[len - 3];
            }
        }
        return sld;
    }
}
