package com.scalekit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a cache operation fails (e.g. Redis unreachable).
 */
public class CacheException extends ScaleKitException {

    public CacheException(String message) {
        super(message, "CACHE_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public CacheException(String message, Throwable cause) {
        super(message, "CACHE_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }
}
