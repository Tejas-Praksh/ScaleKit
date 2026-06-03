package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.constants.SystemConstants;
import com.scalekit.common.exception.ResourceNotFoundException;
import com.scalekit.common.exception.UrlException;
import com.scalekit.common.exception.UrlExpiredException;
import com.scalekit.common.exception.UrlPasswordException;
import com.scalekit.common.util.Base62Encoder;
import com.scalekit.urlshortener.domain.Url;
import com.scalekit.urlshortener.dto.CreateUrlRequest;
import com.scalekit.urlshortener.dto.UpdateUrlRequest;
import com.scalekit.urlshortener.dto.UrlResponse;
import com.scalekit.urlshortener.dto.UrlStatsResponse;
import com.scalekit.urlshortener.repository.UrlRepository;
import com.scalekit.urlshortener.service.CounterService;
import com.scalekit.urlshortener.service.UrlPasswordService;
import com.scalekit.urlshortener.service.UrlService;
import com.scalekit.urlshortener.service.UrlSafetyService;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.dto.SafetyLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Implementation of {@link UrlService} handling high-performance URL shortening operations.
 */
@Service
public class UrlServiceImpl implements UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlServiceImpl.class);
    private static final String CACHE_PREFIX = SystemConstants.REDIS_URL_PREFIX + "cache:";

    private final UrlRepository urlRepository;
    private final CounterService counterService;
    private final StringRedisTemplate stringRedisTemplate;
    private final UrlPasswordService urlPasswordService;
    private final UrlSafetyService urlSafetyService;

    @Value("${scalekit.base-url:http://localhost:8080/}")
    private String baseUrl;

    public UrlServiceImpl(
            UrlRepository urlRepository,
            CounterService counterService,
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate,
            UrlPasswordService urlPasswordService,
            UrlSafetyService urlSafetyService) {
        this.urlRepository = urlRepository;
        this.counterService = counterService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.urlPasswordService = urlPasswordService;
        this.urlSafetyService = urlSafetyService;
    }


    @Override
    @Transactional
    public UrlResponse createUrl(CreateUrlRequest request) {
        log.info("Creating shortened URL for: {}", request.getOriginalUrl());

        String shortCode;
        String customAlias = request.getCustomAlias();

        if (customAlias != null && !customAlias.trim().isEmpty()) {
            customAlias = customAlias.trim();
            // Validate custom alias uniqueness
            if (urlRepository.findByCustomAlias(customAlias).isPresent() || 
                urlRepository.findByShortCode(customAlias).isPresent()) {
                throw new UrlException("Custom alias '" + customAlias + "' is already in use");
            }
            shortCode = customAlias;
        } else {
            // Generate sequential Base62 short code from counter
            long id = counterService.getNextId();
            shortCode = Base62Encoder.encode(id);
            
            // Extreme edge-case collision detection (in case of sequence resets)
            int retries = 0;
            while (urlRepository.findByShortCode(shortCode).isPresent() && retries < 3) {
                log.warn("Collision detected for shortCode '{}'. Retrying generation...", shortCode);
                id = counterService.getNextId();
                shortCode = Base62Encoder.encode(id);
                retries++;
            }
            if (retries >= 3) {
                throw new UrlException("Failed to generate unique short code after 3 attempts");
            }
        }

        // 1. Run safety scanner check before shortening
        SafetyCheckResult safetyResult = urlSafetyService.checkUrl(request.getOriginalUrl());
        Boolean isSafe = true;
        java.util.Map<String, Object> metadata = null;
        if (safetyResult != null) {
            if (safetyResult.getSafetyLevel() == SafetyLevel.DANGEROUS || 
                safetyResult.getSafetyLevel() == SafetyLevel.BLOCKED) {
                throw new UrlException("URL safety check failed: URL is malicious or blocked");
            }
            if (safetyResult.getSafetyLevel() == SafetyLevel.WARNING) {
                isSafe = false;
                metadata = new java.util.HashMap<>();
                metadata.put("reputationScore", safetyResult.getReputationScore());
            }
        }

        // Build entity
        Url url = Url.builder()
                .originalUrl(request.getOriginalUrl())
                .shortCode(shortCode)
                .customAlias(customAlias)
                .expiresAt(request.getExpiresAt())
                .createdBy(request.getCreatedBy())
                .title(request.getTitle())
                .description(request.getDescription())
                .isActive(true)
                .isSafe(isSafe)
                .metadata(metadata)
                .clickCount(0L)
                .uniqueClickCount(0L)
                .build();


        Url savedUrl = urlRepository.save(url);

        // Cache the newly created URL map
        cacheUrl(savedUrl);

        return mapToResponse(savedUrl);
    }

    @Override
    public String resolveUrl(String shortCode) {
        log.debug("Resolving short code: {}", shortCode);

        // 1. Try Cache First
        if (stringRedisTemplate != null) {
            String cacheKey = CACHE_PREFIX + shortCode;
            try {
                String cachedUrl = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cachedUrl != null) {
                    log.debug("Cache hit for short code '{}' -> '{}'", shortCode, cachedUrl);
                    return cachedUrl;
                }
            } catch (Exception e) {
                log.warn("Redis lookup failed for short code '{}', proceeding to DB: {}", shortCode, e.getMessage());
            }
        }

        // 2. Fallback to DB
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        // 3. Validate Status
        if (Boolean.FALSE.equals(url.getIsActive())) {
            throw new ResourceNotFoundException("URL", shortCode); // Deleted URLs return 404
        }
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
            // Expired URLs return 410 Gone, and we delete from cache if existed
            evictCache(shortCode);
            throw new UrlExpiredException(shortCode);
        }

        // 4. Update Cache for future requests
        cacheUrl(url);

        return url.getOriginalUrl();
    }

    @Override
    public UrlResponse getUrl(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        if (Boolean.FALSE.equals(url.getIsActive())) {
            throw new ResourceNotFoundException("URL", shortCode);
        }

        return mapToResponse(url);
    }

    @Override
    public UrlStatsResponse getUrlStats(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        if (Boolean.FALSE.equals(url.getIsActive())) {
            throw new ResourceNotFoundException("URL", shortCode);
        }

        boolean expired = url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now());

        return UrlStatsResponse.builder()
                .shortCode(url.getShortCode())
                .originalUrl(url.getOriginalUrl())
                .totalClicks(url.getClickCount())
                .uniqueClicks(url.getUniqueClickCount())
                .createdAt(url.getCreatedAt())
                .lastAccessedAt(url.getLastAccessedAt())
                .active(url.getIsActive())
                .expired(expired)
                .build();
    }

    @Override
    @Transactional
    public void deleteUrl(String shortCode) {
        log.info("Deleting URL with short code: {}", shortCode);
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        if (Boolean.TRUE.equals(url.getIsActive())) {
            url.setIsActive(false);
            urlRepository.save(url);

            // Invalidate cache immediately on soft-delete
            evictCache(shortCode);
        }
    }

    @Override
    @Transactional
    public UrlResponse updateUrl(String shortCode, UpdateUrlRequest request) {
        log.info("Updating URL with short code: {}", shortCode);
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        if (Boolean.FALSE.equals(url.getIsActive())) {
            throw new ResourceNotFoundException("URL", shortCode);
        }

        if (request.getTitle() != null) {
            url.setTitle(request.getTitle());
        }
        if (request.getExpiresAt() != null) {
            url.setExpiresAt(request.getExpiresAt());
        }
        if (request.getIsActive() != null) {
            url.setIsActive(request.getIsActive());
        }
        if (request.getCustomAlias() != null) {
            String newAlias = request.getCustomAlias().trim();
            // Validate uniqueness (excluding current URL)
            if (urlRepository.findByCustomAlias(newAlias)
                    .filter(u -> !u.getId().equals(url.getId())).isPresent()) {
                throw new UrlException("Custom alias '" + newAlias + "' is already in use");
            }
            url.setCustomAlias(newAlias);
        }

        Url updated = urlRepository.save(url);
        evictCache(shortCode);
        cacheUrl(updated);

        return mapToResponse(updated);
    }

    @Override
    public boolean existsByShortCode(String shortCode) {
        return urlRepository.findByShortCode(shortCode).isPresent();
    }

    @Override
    public String resolveUrlWithPassword(String shortCode, String password, String tempToken) {
        log.debug("Resolving password-protected URL for short code: {}", shortCode);

        // First: validate basic redirect (active, expiry) without password check
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("URL", shortCode));

        if (Boolean.FALSE.equals(url.getIsActive())) {
            throw new ResourceNotFoundException("URL", shortCode);
        }
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
            evictCache(shortCode);
            throw new UrlExpiredException(shortCode);
        }

        // If not password protected, behave like normal resolveUrl
        if (!Boolean.TRUE.equals(url.getIsPasswordProtected())) {
            cacheUrl(url);
            return url.getOriginalUrl();
        }

        // Check temp token first (no password re-entry needed)
        if (tempToken != null && urlPasswordService.validateTempToken(shortCode, tempToken)) {
            cacheUrl(url);
            return url.getOriginalUrl();
        }

        // Verify provided password
        if (password != null && url.getPasswordHash() != null
                && urlPasswordService.verifyPassword(password, url.getPasswordHash())) {
            cacheUrl(url);
            return url.getOriginalUrl();
        }

        throw new UrlPasswordException();
    }

    private void cacheUrl(Url url) {
        if (Boolean.FALSE.equals(url.getIsActive()) || stringRedisTemplate == null) {
            return;
        }

        String cacheKey = CACHE_PREFIX + url.getShortCode();
        try {
            if (url.getExpiresAt() != null) {
                long ttlSecs = Duration.between(Instant.now(), url.getExpiresAt()).getSeconds();
                if (ttlSecs > 0) {
                    stringRedisTemplate.opsForValue().set(cacheKey, url.getOriginalUrl(), Duration.ofSeconds(ttlSecs));
                }
            } else {
                // Default cache duration for permanent URLs is 7 days to preserve memory
                stringRedisTemplate.opsForValue().set(cacheKey, url.getOriginalUrl(), Duration.ofDays(7));
            }
        } catch (Exception e) {
            log.warn("Failed to write to Redis cache for key '{}': {}", cacheKey, e.getMessage());
        }
    }

    private void evictCache(String shortCode) {
        if (stringRedisTemplate == null) {
            return;
        }
        String cacheKey = CACHE_PREFIX + shortCode;
        try {
            stringRedisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.warn("Failed to evict Redis cache key '{}': {}", cacheKey, e.getMessage());
        }
    }

    private UrlResponse mapToResponse(Url url) {
        boolean expired = url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now());
        String shortUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + url.getShortCode();

        return UrlResponse.builder()
                .shortCode(url.getShortCode())
                .shortUrl(shortUrl)
                .originalUrl(url.getOriginalUrl())
                .customAlias(url.getCustomAlias())
                .createdAt(url.getCreatedAt())
                .expiresAt(url.getExpiresAt())
                .active(url.getIsActive())
                .clickCount(url.getClickCount())
                .uniqueClickCount(url.getUniqueClickCount())
                .lastAccessedAt(url.getLastAccessedAt())
                .createdBy(url.getCreatedBy())
                .title(url.getTitle())
                .description(url.getDescription())
                .expired(expired)
                .build();
    }
}
