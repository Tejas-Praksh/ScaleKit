package com.scalekit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown for URL shortener-specific validation errors.
 */
public class UrlException extends ScaleKitException {

    public UrlException(String message) {
        super(message, "URL_ERROR", HttpStatus.BAD_REQUEST);
    }

    public UrlException(String message, Object details) {
        super(message, "URL_ERROR", HttpStatus.BAD_REQUEST, details);
    }
}
