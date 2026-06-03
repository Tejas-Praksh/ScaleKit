package com.scalekit.ratelimiter.algorithm;

/**
 * Supported rate limiting algorithms in ScaleKit.
 */
public enum RateLimitAlgorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW,
    FIXED_WINDOW,
    ADAPTIVE
}
