package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.service.UrlPasswordService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * BCrypt + JWT implementation of {@link UrlPasswordService}.
 *
 * <p>Passwords are hashed with BCrypt strength 10.
 * Temporary tokens are JWTs signed with an HMAC-SHA256 key and
 * stored in Redis so they can be invalidated before expiry if needed.
 */
@Service
public class UrlPasswordServiceImpl implements UrlPasswordService {

    private static final Logger log = LoggerFactory.getLogger(UrlPasswordServiceImpl.class);

    private static final int BCRYPT_STRENGTH = 10;
    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final String TOKEN_REDIS_PREFIX = "url:token:";

    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;
    private final SecretKey jwtSigningKey;

    public UrlPasswordServiceImpl(
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate,
            @Value("${scalekit.jwt.secret:scalekit-default-secret-key-change-in-production-min32chars}") String jwtSecret) {
        this.passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        this.stringRedisTemplate = stringRedisTemplate;
        // Ensure key is at least 256 bits for HS256
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            keyBytes = java.util.Arrays.copyOf(keyBytes, 32);
        }
        this.jwtSigningKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public String hashPassword(String plainPassword) {
        return passwordEncoder.encode(plainPassword);
    }

    @Override
    public boolean verifyPassword(String plainPassword, String hashedPassword) {
        return passwordEncoder.matches(plainPassword, hashedPassword);
    }

    @Override
    public String generateTempAccessToken(String shortCode) {
        String jti = UUID.randomUUID().toString();
        Date expiry = new Date(System.currentTimeMillis() + TOKEN_TTL.toMillis());

        String token = Jwts.builder()
                .subject(shortCode)
                .id(jti)
                .expiration(expiry)
                .signWith(jwtSigningKey)
                .compact();

        // Store in Redis for explicit invalidation support
        if (stringRedisTemplate != null) {
            try {
                String redisKey = TOKEN_REDIS_PREFIX + shortCode + ":" + jti;
                stringRedisTemplate.opsForValue().set(redisKey, "valid", TOKEN_TTL);
            } catch (Exception e) {
                log.warn("Failed to store temp token in Redis for '{}': {}", shortCode, e.getMessage());
            }
        }

        return token;
    }

    @Override
    public boolean validateTempToken(String shortCode, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            var claims = Jwts.parser()
                    .verifyWith(jwtSigningKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Verify the token was issued for this specific short code
            if (!shortCode.equals(claims.getSubject())) {
                return false;
            }

            // Optionally verify token still exists in Redis (supports early revocation)
            if (stringRedisTemplate != null) {
                try {
                    String jti = claims.getId();
                    String redisKey = TOKEN_REDIS_PREFIX + shortCode + ":" + jti;
                    return Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey));
                } catch (Exception e) {
                    log.warn("Redis token validation failed, falling back to JWT-only check: {}", e.getMessage());
                    // Fall through — JWT signature check already passed
                }
            }

            return true;
        } catch (JwtException e) {
            log.debug("Invalid temp token for '{}': {}", shortCode, e.getMessage());
            return false;
        }
    }
}
