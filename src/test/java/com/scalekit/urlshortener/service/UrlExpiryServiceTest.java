package com.scalekit.urlshortener.service;

import com.scalekit.common.exception.UrlExpiredException;
import com.scalekit.urlshortener.domain.Url;
import com.scalekit.urlshortener.service.impl.UrlExpiryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

class UrlExpiryServiceTest {

    private UrlExpiryService expiryService;

    @BeforeEach
    void setUp() {
        expiryService = new UrlExpiryServiceImpl();
    }

    // ── validateExpiry ─────────────────────────────────────────────────────

    @Test
    void validateExpiry_notExpired_noException() {
        Url url = Url.builder()
                .shortCode("abc1234")
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();

        assertThatNoException().isThrownBy(() -> expiryService.validateExpiry(url));
    }

    @Test
    void validateExpiry_expired_throwsUrlExpiredException() {
        Url url = Url.builder()
                .shortCode("expired1")
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();

        assertThatThrownBy(() -> expiryService.validateExpiry(url))
                .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    void validateExpiry_nullExpiry_noException() {
        Url url = Url.builder()
                .shortCode("noexpiry")
                .expiresAt(null)
                .build();

        assertThatNoException().isThrownBy(() -> expiryService.validateExpiry(url));
    }

    // ── calculateExpiryDate ────────────────────────────────────────────────

    @Test
    void calculateExpiry_validDays_returnsCorrectDate() {
        Instant before = Instant.now();
        Instant result = expiryService.calculateExpiryDate(30);
        Instant after = Instant.now();

        // Result should be ~30 days from now
        assertThat(result).isAfterOrEqualTo(before.plus(30, ChronoUnit.DAYS).minusSeconds(2));
        assertThat(result).isBeforeOrEqualTo(after.plus(30, ChronoUnit.DAYS).plusSeconds(2));
    }

    @Test
    void calculateExpiry_over365_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> expiryService.calculateExpiryDate(366))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365");
    }

    @Test
    void calculateExpiry_zeroDays_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> expiryService.calculateExpiryDate(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── isExpired ──────────────────────────────────────────────────────────

    @Test
    void isExpired_nullExpiry_returnsFalse() {
        Url url = Url.builder().shortCode("abc").expiresAt(null).build();
        assertThat(expiryService.isExpired(url)).isFalse();
    }

    @Test
    void isExpired_futureExpiry_returnsFalse() {
        Url url = Url.builder()
                .shortCode("abc")
                .expiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .build();
        assertThat(expiryService.isExpired(url)).isFalse();
    }

    @Test
    void isExpired_pastExpiry_returnsTrue() {
        Url url = Url.builder()
                .shortCode("abc")
                .expiresAt(Instant.now().minus(1, ChronoUnit.SECONDS))
                .build();
        assertThat(expiryService.isExpired(url)).isTrue();
    }
}
