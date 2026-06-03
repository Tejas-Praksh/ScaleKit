package com.scalekit.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a distributed lock cannot be acquired.
 */
@Getter
public class DistributedLockException extends ScaleKitException {

    private final String lockKey;

    public DistributedLockException(String lockKey) {
        super(
                "Failed to acquire lock: " + lockKey,
                "LOCK_ACQUISITION_FAILED",
                HttpStatus.CONFLICT
        );
        this.lockKey = lockKey;
    }
}
