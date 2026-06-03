package com.scalekit.urlshortener.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.util.HashUtil;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.service.UrlReputationCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache implementation using Redis with in-memory ConcurrentHashMap fallback.
 */
@Service
public class UrlReputationCacheImpl implements UrlReputationCache {

    private static final Logger log = LoggerFactory.getLogger(UrlReputationCacheImpl.class);
    private static final String CACHE_KEY_PREFIX = "safety:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // Thread-safe local cache fallback for robustness when Redis is unavailable
    private final ConcurrentHashMap<String, LocalCacheEntry> localCache = new ConcurrentHashMap<>();

    public UrlReputationCacheImpl(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void cacheResult(String url, SafetyCheckResult result) {
        if (url == null || result == null) {
            return;
        }
        String hash = HashUtil.sha256(url);
        String key = CACHE_KEY_PREFIX + hash;

        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(key, json, Duration.ofHours(24));
                return;
            } catch (Exception e) {
                log.warn("Redis safety cache write failed, falling back to local cache: {}", e.getMessage());
            }
        }

        localCache.put(hash, new LocalCacheEntry(result, Instant.now().plus(Duration.ofHours(24))));
    }

    @Override
    public Optional<SafetyCheckResult> getCachedResult(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String hash = HashUtil.sha256(url);
        String key = CACHE_KEY_PREFIX + hash;

        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    SafetyCheckResult result = objectMapper.readValue(json, SafetyCheckResult.class);
                    result.setFromCache(true);
                    return Optional.of(result);
                }
            } catch (Exception e) {
                log.warn("Redis safety cache read failed, checking local cache: {}", e.getMessage());
            }
        }

        LocalCacheEntry entry = localCache.get(hash);
        if (entry != null) {
            if (entry.isExpired()) {
                localCache.remove(hash);
            } else {
                SafetyCheckResult result = entry.getResult();
                result.setFromCache(true);
                return Optional.of(result);
            }
        }

        return Optional.empty();
    }

    private static class LocalCacheEntry {
        private final SafetyCheckResult result;
        private final Instant expiresAt;

        public LocalCacheEntry(SafetyCheckResult result, Instant expiresAt) {
            this.result = result;
            this.expiresAt = expiresAt;
        }

        public SafetyCheckResult getResult() {
            return result;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
