package com.scalekit.urlshortener.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.util.HashUtil;
import com.scalekit.urlshortener.dto.UrlPreviewDto;
import com.scalekit.urlshortener.service.UrlPreviewService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * jsoup-based implementation of {@link UrlPreviewService}.
 *
 * <p>Fetches and parses Open Graph tags with a 3-second timeout.
 * Results are cached in Redis for 24 hours using the SHA-256 hash
 * of the URL as the cache key to keep key lengths bounded.
 */
@Service
public class UrlPreviewServiceImpl implements UrlPreviewService {

    private static final Logger log = LoggerFactory.getLogger(UrlPreviewServiceImpl.class);

    private static final String PREVIEW_CACHE_PREFIX = "url:preview:";
    private static final Duration PREVIEW_CACHE_TTL = Duration.ofHours(24);
    private static final int FETCH_TIMEOUT_MS = 3_000;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public UrlPreviewServiceImpl(
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UrlPreviewDto> getPreview(String url) {
        String cacheKey = PREVIEW_CACHE_PREFIX + HashUtil.sha256(url);

        // 1. Cache read
        if (stringRedisTemplate != null) {
            try {
                String cached = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    log.debug("Preview cache HIT for '{}'", url);
                    return Optional.of(objectMapper.readValue(cached, UrlPreviewDto.class));
                }
            } catch (Exception e) {
                log.warn("Preview cache read failed for '{}': {}", url, e.getMessage());
            }
        }

        // 2. Fetch and parse
        log.debug("Preview cache MISS for '{}', fetching...", url);
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(FETCH_TIMEOUT_MS)
                    .userAgent("ScaleKit-Preview-Bot/1.0")
                    .get();

            Map<String, String> ogTags = extractOgTags(doc);

            String title = ogTags.getOrDefault("og:title", doc.title());
            String description = ogTags.get("og:description");
            String imageUrl = ogTags.get("og:image");
            String siteName = ogTags.get("og:site_name");
            String favicon = ogTags.get("favicon");

            UrlPreviewDto preview = UrlPreviewDto.builder()
                    .url(url)
                    .title(title)
                    .description(description)
                    .imageUrl(imageUrl)
                    .siteName(siteName)
                    .favicon(favicon)
                    .isSafe(true)
                    .scrapedAt(Instant.now())
                    .build();

            // 3. Cache the result
            if (stringRedisTemplate != null) {
                try {
                    String json = objectMapper.writeValueAsString(preview);
                    stringRedisTemplate.opsForValue().set(cacheKey, json, PREVIEW_CACHE_TTL);
                } catch (Exception e) {
                    log.warn("Preview cache write failed for '{}': {}", url, e.getMessage());
                }
            }

            return Optional.of(preview);

        } catch (IOException e) {
            log.warn("Failed to fetch preview for '{}': {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts OG meta tags and favicon from the parsed HTML document.
     */
    private Map<String, String> extractOgTags(Document doc) {
        Map<String, String> tags = new HashMap<>();

        // OG meta tags: <meta property="og:*" content="...">
        Elements metaTags = doc.select("meta[property^=og:]");
        for (Element meta : metaTags) {
            String property = meta.attr("property");
            String content = meta.attr("content");
            if (!content.isEmpty()) {
                tags.put(property, content);
            }
        }

        // Favicon: <link rel="icon" href="...">
        Element faviconEl = doc.selectFirst("link[rel~=(?i)icon]");
        if (faviconEl != null) {
            String href = faviconEl.absUrl("href");
            if (!href.isEmpty()) {
                tags.put("favicon", href);
            }
        }

        return tags;
    }
}
