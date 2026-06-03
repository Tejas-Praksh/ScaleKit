package com.scalekit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a distributed lock cannot be acquired.
 */
public class LockNotAcquiredException extends ScaleKitException {

    public LockNotAcquiredException(String lockKey) {
        super("Failed to acquire lock: " + lockKey, "LOCK_ACQUISITION_FAILED", HttpStatus.CONFLICT);
    }

    public LockNotAcquiredException(String lockKey, String message) {
        super(message, "LOCK_ACQUISITION_FAILED", HttpStatus.CONFLICT);
    }
}
