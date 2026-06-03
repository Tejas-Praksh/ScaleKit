package com.scalekit.cache.service;

import com.scalekit.cache.dto.UrlDuplicateStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UrlDuplicateDetector} — duplicate detection and URL normalization.
 */
class UrlDuplicateDetectorTest {

    private UrlDuplicateDetector detector;

    @BeforeEach
    void setUp() {
        detector = new UrlDuplicateDetector();
        detector.init(); // manually trigger @PostConstruct
    }

    // ── Duplicate Detection ─────────────────────────────────────────────

    @Test
    void isDuplicate_newUrl_returnsFalse() {
        assertFalse(detector.isDuplicate("https://example.com/page1"));
    }

    @Test
    void isDuplicate_seenUrl_returnsTrue() {
        detector.markAsSeen("https://example.com/page1");
        assertTrue(detector.isDuplicate("https://example.com/page1"));
    }

    @Test
    void isDuplicate_differentUrls_returnsFalse() {
        detector.markAsSeen("https://example.com/page1");
        assertFalse(detector.isDuplicate("https://example.com/page2"));
    }

    @Test
    void isDuplicate_normalizedMatch_returnsTrue() {
        // These should normalize to the same URL
        detector.markAsSeen("https://www.example.com/page");
        assertTrue(detector.isDuplicate("https://example.com/page"));
    }

    // ── URL Normalization ───────────────────────────────────────────────

    @Test
    void normalizeUrl_removesTrackingParams() {
        String raw = "https://example.com/page?utm_source=google&utm_medium=cpc&id=123";
        String normalized = detector.normalizeUrl(raw);
        assertFalse(normalized.contains("utm_source"));
        assertFalse(normalized.contains("utm_medium"));
        assertTrue(normalized.contains("id=123"));
    }

    @Test
    void normalizeUrl_removesFbclid() {
        String raw = "https://example.com/page?fbclid=abc123&real=param";
        String normalized = detector.normalizeUrl(raw);
        assertFalse(normalized.contains("fbclid"));
        assertTrue(normalized.contains("real=param"));
    }

    @Test
    void normalizeUrl_removesGclid() {
        String raw = "https://example.com/page?gclid=xyz789";
        String normalized = detector.normalizeUrl(raw);
        assertFalse(normalized.contains("gclid"));
    }

    @Test
    void normalizeUrl_lowercaseDomain() {
        String raw = "https://EXAMPLE.COM/Path";
        String normalized = detector.normalizeUrl(raw);
        assertTrue(normalized.contains("example.com"));
        // Path should preserve case
        assertTrue(normalized.contains("/Path"));
    }

    @Test
    void normalizeUrl_removesWww() {
        String raw = "https://www.example.com/page";
        String normalized = detector.normalizeUrl(raw);
        assertFalse(normalized.contains("www."));
        assertTrue(normalized.contains("example.com"));
    }

    @Test
    void normalizeUrl_removesTrailingSlash() {
        String withSlash = detector.normalizeUrl("https://example.com/page/");
        String withoutSlash = detector.normalizeUrl("https://example.com/page");
        assertEquals(withSlash, withoutSlash);
    }

    @Test
    void normalizeUrl_sortsQueryParams() {
        String url1 = "https://example.com/page?b=2&a=1";
        String url2 = "https://example.com/page?a=1&b=2";
        assertEquals(detector.normalizeUrl(url1), detector.normalizeUrl(url2));
    }

    @Test
    void normalizeUrl_handlesNoQuery() {
        String normalized = detector.normalizeUrl("https://example.com/page");
        assertEquals("https://example.com/page", normalized);
    }

    @Test
    void normalizeUrl_handlesEmptyInput() {
        assertEquals("", detector.normalizeUrl(""));
        assertEquals("", detector.normalizeUrl(null));
    }

    // ── Stats ───────────────────────────────────────────────────────────

    @Test
    void getStats_tracksAdditionsAndDuplicates() {
        detector.markAsSeen("https://a.com");
        detector.markAsSeen("https://b.com");
        detector.isDuplicate("https://a.com"); // should be duplicate

        UrlDuplicateStats stats = detector.getStats();
        assertEquals(2, stats.getUrlsAdded());
        assertEquals(1, stats.getDuplicatesBlocked());
    }
}
