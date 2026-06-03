package com.scalekit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an expired URL is accessed. Returns HTTP 410 Gone.
 *
 * <p>Per RFC 9110, 410 Gone signals the resource was intentionally
 * removed and is not coming back — more specific than 404 Not Found.
 */
public class UrlExpiredException extends ScaleKitException {

    public UrlExpiredException(String shortCode) {
        super("URL has expired: " + shortCode, "URL_EXPIRED", HttpStatus.GONE);
    }
}
