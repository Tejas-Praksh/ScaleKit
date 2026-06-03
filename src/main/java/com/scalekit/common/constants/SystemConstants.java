package com.scalekit.common.constants;

/**
 * Centralized constants for all ScaleKit systems.
 *
 * <p>Eliminates magic strings and numbers across the codebase.
 * Organized by subsystem for discoverability.
 */
public final class SystemConstants {

    private SystemConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }

    // ── URL Shortener ──────────────────────────────────────────────
    public static final int URL_SHORT_CODE_LENGTH = 7;
    public static final int URL_MAX_CUSTOM_ALIAS_LENGTH = 20;
    public static final int URL_DEFAULT_EXPIRY_DAYS = 30;
    public static final int URL_MAX_EXPIRY_DAYS = 365;
    public static final String BASE62_CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // ── Rate Limiter ───────────────────────────────────────────────
    public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 100;
    public static final int DEFAULT_BURST_SIZE = 20;
    public static final String RATE_LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    public static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    // ── Cache ──────────────────────────────────────────────────────
    public static final int DEFAULT_CACHE_TTL_SECONDS = 3600;
    public static final int MAX_CACHE_SIZE = 10_000;
    public static final String CACHE_HIT_HEADER = "X-Cache";
    public static final String CACHE_HIT_VALUE = "HIT";
    public static final String CACHE_MISS_VALUE = "MISS";

    // ── Redis Key Prefixes ─────────────────────────────────────────
    public static final String REDIS_URL_PREFIX = "url:";
    public static final String REDIS_RATE_LIMIT_PREFIX = "rl:";
    public static final String REDIS_CACHE_PREFIX = "cache:";
    public static final String REDIS_LOCK_PREFIX = "lock:";
    public static final String REDIS_ANALYTICS_PREFIX = "analytics:";
    public static final String REDIS_BLOOM_PREFIX = "bloom:";

    // ── HTTP Headers ───────────────────────────────────────────────
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String EXECUTION_TIME_HEADER = "X-Execution-Time";

    // ── Micrometer Metric Names ────────────────────────────────────
    public static final String METRIC_URL_CREATED = "scalekit.url.created";
    public static final String METRIC_URL_REDIRECTED = "scalekit.url.redirected";
    public static final String METRIC_RATE_LIMIT_HIT = "scalekit.ratelimit.hit";
    public static final String METRIC_CACHE_HIT = "scalekit.cache.hit";
    public static final String METRIC_CACHE_MISS = "scalekit.cache.miss";
}
