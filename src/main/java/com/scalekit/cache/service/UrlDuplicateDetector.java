package com.scalekit.cache.service;

import com.scalekit.cache.algorithm.ScalableBloomFilter;
import com.scalekit.cache.dto.BloomFilterStats;
import com.scalekit.cache.dto.UrlDuplicateStats;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * URL duplicate detection service backed by a Scalable Bloom Filter.
 *
 * <p>Before persisting a URL, call {@link #isDuplicate(String)} to check whether
 * it has already been seen. Normalizes URLs to handle cosmetic differences
 * (trailing slashes, tracking params, query-param ordering).
 */
@Service
@Slf4j
public class UrlDuplicateDetector {

    private static final int INITIAL_CAPACITY = 100_000;
    private static final double TARGET_FPR = 0.001; // 0.1%

    /** Tracking parameters to strip during normalization. */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "dclid", "msclkid", "twclid",
            "mc_cid", "mc_eid", "ref", "hsa_acc", "hsa_cam"
    );

    private ScalableBloomFilter urlFilter;
    private final AtomicLong duplicatesBlocked = new AtomicLong();
    private final AtomicLong urlsAdded = new AtomicLong();

    @PostConstruct
    void init() {
        urlFilter = new ScalableBloomFilter(INITIAL_CAPACITY, TARGET_FPR);
        log.info("UrlDuplicateDetector initialized: capacity={}, targetFPR={}", INITIAL_CAPACITY, TARGET_FPR);
    }

    /**
     * Checks whether a URL has likely been seen before.
     *
     * @param url the raw URL to check
     * @return {@code true} if the URL was probably already added (possible false positive);
     *         {@code false} if it was <strong>definitely never seen</strong>
     */
    public boolean isDuplicate(String url) {
        String normalized = normalizeUrl(url);
        boolean seen = urlFilter.mightContain(normalized);
        if (seen) {
            duplicatesBlocked.incrementAndGet();
            log.debug("Potential duplicate URL detected: {}", normalized);
        }
        return seen;
    }

    /**
     * Marks a URL as seen by adding it to the Bloom Filter.
     */
    public void markAsSeen(String url) {
        String normalized = normalizeUrl(url);
        urlFilter.add(normalized);
        urlsAdded.incrementAndGet();
    }

    /**
     * Returns statistics for the duplicate detector.
     */
    public UrlDuplicateStats getStats() {
        BloomFilterStats activeStats = urlFilter.getActiveFilterStats();
        return UrlDuplicateStats.builder()
                .urlsAdded(urlsAdded.get())
                .duplicatesBlocked(duplicatesBlocked.get())
                .estimatedFPR(activeStats.getEstimatedCurrentFPR())
                .filterStats(activeStats)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // URL Normalization
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Normalizes a URL to handle cosmetic differences:
     * <ul>
     *   <li>Lowercases scheme and host</li>
     *   <li>Removes {@code www.} prefix</li>
     *   <li>Removes trailing slash</li>
     *   <li>Strips tracking parameters (utm_*, fbclid, gclid, etc.)</li>
     *   <li>Sorts remaining query parameters alphabetically</li>
     * </ul>
     */
    public String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }

        try {
            // Ensure scheme is present for URI parsing
            String working = url.trim();
            if (!working.contains("://")) {
                working = "https://" + working;
            }

            URI uri = URI.create(working);

            // Lowercase scheme and host
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";

            // Remove www. prefix
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // Path: remove trailing slash (but keep "/" for root)
            String path = uri.getRawPath() != null ? uri.getRawPath() : "";
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            // Query: strip tracking params and sort alphabetically
            String query = uri.getRawQuery();
            String normalizedQuery = normalizeQuery(query);

            // Reconstruct
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                sb.append(":").append(uri.getPort());
            }
            sb.append(path);
            if (!normalizedQuery.isEmpty()) {
                sb.append("?").append(normalizedQuery);
            }

            return sb.toString();
        } catch (Exception e) {
            log.debug("URL normalization fallback for: {}", url);
            // Fallback: lowercase and trim
            return url.trim().toLowerCase();
        }
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        return Arrays.stream(query.split("&"))
                .map(param -> {
                    try {
                        return URLDecoder.decode(param, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return param;
                    }
                })
                .filter(param -> {
                    String key = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
                    return !TRACKING_PARAMS.contains(key.toLowerCase());
                })
                .sorted()
                .collect(Collectors.joining("&"));
    }
}
