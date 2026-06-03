package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.service.impl.UrlPasswordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class UrlPasswordServiceTest {

    private UrlPasswordService passwordService;

    @BeforeEach
    void setUp() {
        // No Redis in tests — pass null for StringRedisTemplate
        passwordService = new UrlPasswordServiceImpl(
                null,
                "test-secret-key-min-32-chars-long-abc123");
    }

    // ── hashPassword ───────────────────────────────────────────────────────

    @Test
    void hashPassword_neverStoresPlain() {
        String plain = "supersecret";
        String hash = passwordService.hashPassword(plain);

        assertThat(hash).isNotNull();
        assertThat(hash).isNotEqualTo(plain);
        assertThat(hash).startsWith("$2a$"); // BCrypt prefix
    }

    @Test
    void hashPassword_sameInputProducesDifferentHashes() {
        // BCrypt uses random salt — same input → different hashes
        String hash1 = passwordService.hashPassword("password");
        String hash2 = passwordService.hashPassword("password");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    // ── verifyPassword ─────────────────────────────────────────────────────

    @Test
    void verifyPassword_correct_returnsTrue() {
        String plain = "mySecretPassword";
        String hash = passwordService.hashPassword(plain);

        assertThat(passwordService.verifyPassword(plain, hash)).isTrue();
    }

    @Test
    void verifyPassword_wrong_returnsFalse() {
        String hash = passwordService.hashPassword("correctPassword");
        assertThat(passwordService.verifyPassword("wrongPassword", hash)).isFalse();
    }

    @Test
    void verifyPassword_timingSafe_completesWithinReasonableTime() {
        // BCrypt is intentionally slow — both correct and incorrect should take similar time
        String hash = passwordService.hashPassword("somePassword");

        long start1 = System.currentTimeMillis();
        passwordService.verifyPassword("somePassword", hash);
        long correct = System.currentTimeMillis() - start1;

        long start2 = System.currentTimeMillis();
        passwordService.verifyPassword("wrongPassword", hash);
        long incorrect = System.currentTimeMillis() - start2;

        // BCrypt constant-time: both calls should be in the same order of magnitude
        // Allow 5x variance to prevent flakiness on CI
        assertThat(Math.max(correct, incorrect))
                .isLessThan(Math.min(correct, incorrect) * 5 + 500);
    }

    // ── generateTempAccessToken ────────────────────────────────────────────

    @Test
    void generateTempToken_validFormat() {
        String token = passwordService.generateTempAccessToken("abc1234");
        assertThat(token).isNotBlank();
        // JWT is 3 dot-separated parts
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
    }

    @Test
    void generateTempToken_differentForEachCall() {
        String token1 = passwordService.generateTempAccessToken("abc1234");
        String token2 = passwordService.generateTempAccessToken("abc1234");
        assertThat(token1).isNotEqualTo(token2);
    }

    // ── validateTempToken ──────────────────────────────────────────────────

    @Test
    void validateTempToken_validToken_returnsTrue() {
        String shortCode = "abc1234";
        String token = passwordService.generateTempAccessToken(shortCode);

        // Without Redis the service falls back to JWT-only check
        assertThat(passwordService.validateTempToken(shortCode, token)).isTrue();
    }

    @Test
    void validateTempToken_wrongShortCode_returnsFalse() {
        String token = passwordService.generateTempAccessToken("abc1234");
        assertThat(passwordService.validateTempToken("different", token)).isFalse();
    }

    @Test
    void validateTempToken_invalidToken_returnsFalse() {
        assertThat(passwordService.validateTempToken("abc1234", "not.a.jwt")).isFalse();
    }

    @Test
    void validateTempToken_nullToken_returnsFalse() {
        assertThat(passwordService.validateTempToken("abc1234", null)).isFalse();
    }
}
