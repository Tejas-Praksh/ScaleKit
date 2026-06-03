package com.scalekit.common.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Thread-safe correlation ID management via SLF4J MDC.
 *
 * <p>Sets a unique correlation ID per request for distributed tracing
 * and log correlation across all systems.
 */
public final class CorrelationIdUtil {

    public static final String CORRELATION_ID_KEY = "correlationId";

    private CorrelationIdUtil() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    /**
     * Generates a new UUID-based correlation ID.
     */
    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Sets the correlation ID in the MDC for the current thread.
     */
    public static void set(String correlationId) {
        MDC.put(CORRELATION_ID_KEY, correlationId);
    }

    /**
     * Retrieves the correlation ID from the MDC for the current thread.
     *
     * @return correlation ID, or {@code null} if not set
     */
    public static String get() {
        return MDC.get(CORRELATION_ID_KEY);
    }

    /**
     * Removes the correlation ID from the MDC.
     * Should be called in a finally block to prevent thread-local leaks.
     */
    public static void clear() {
        MDC.remove(CORRELATION_ID_KEY);
    }
}
