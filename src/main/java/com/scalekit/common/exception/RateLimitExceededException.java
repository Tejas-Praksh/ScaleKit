package com.scalekit.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a client exceeds the configured rate limit.
 */
@Getter
public class RateLimitExceededException extends ScaleKitException {

    private final String identifier;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String identifier, long retryAfterSeconds) {
        super(
                String.format("Rate limit exceeded for: %s. Retry after %d seconds", identifier, retryAfterSeconds),
                "RATE_LIMIT_EXCEEDED",
                HttpStatus.TOO_MANY_REQUESTS
        );
        this.identifier = identifier;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
