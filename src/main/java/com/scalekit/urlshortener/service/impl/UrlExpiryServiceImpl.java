package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.exception.UrlExpiredException;
import com.scalekit.urlshortener.domain.Url;
import com.scalekit.urlshortener.service.UrlExpiryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Default implementation of {@link UrlExpiryService}.
 *
 * <p>Stateless — all methods are pure functions of the input,
 * making this class trivially unit-testable without mocks.
 */
@Service
public class UrlExpiryServiceImpl implements UrlExpiryService {

    private static final int MIN_EXPIRY_DAYS = 1;
    private static final int MAX_EXPIRY_DAYS = 365;

    @Override
    public void validateExpiry(Url url) {
        if (isExpired(url)) {
            throw new UrlExpiredException(url.getShortCode());
        }
    }

    @Override
    public Instant calculateExpiryDate(int days) {
        if (days < MIN_EXPIRY_DAYS || days > MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException(
                    "Expiry days must be between " + MIN_EXPIRY_DAYS + " and " + MAX_EXPIRY_DAYS
                    + ", but got: " + days);
        }
        return Instant.now().plus(days, ChronoUnit.DAYS);
    }

    @Override
    public boolean isExpired(Url url) {
        if (url == null || url.getExpiresAt() == null) {
            return false;
        }
        return url.getExpiresAt().isBefore(Instant.now());
    }
}
