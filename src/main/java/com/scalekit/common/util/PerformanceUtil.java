package com.scalekit.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Lightweight performance measurement utilities.
 *
 * <p>Uses {@link System#nanoTime()} for monotonic, high-resolution timing.
 */
public final class PerformanceUtil {

    private static final Logger log = LoggerFactory.getLogger(PerformanceUtil.class);

    private PerformanceUtil() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    /**
     * Captures the current time in nanoseconds.
     */
    public static long startTimer() {
        return System.nanoTime();
    }

    /**
     * Computes elapsed milliseconds since {@code startTime}.
     */
    public static long elapsedMs(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000;
    }

    /**
     * Executes a task, logs its duration, and returns elapsed milliseconds.
     *
     * @param operation human-readable operation name for the log entry
     * @param task      the work to execute
     * @return elapsed time in milliseconds
     */
    public static long measureAndLog(String operation, Runnable task) {
        long start = startTimer();
        try {
            task.run();
        } finally {
            long elapsed = elapsedMs(start);
            log.info("[PERF] {} completed in {} ms", operation, elapsed);
            return elapsed;
        }
    }

    /**
     * Executes a supplier, logs its duration, and returns the result.
     *
     * @param operation human-readable operation name for the log entry
     * @param task      the work to execute
     * @param <T>       return type
     * @return the supplier's result
     */
    public static <T> T measureAndReturn(String operation, Supplier<T> task) {
        long start = startTimer();
        try {
            return task.get();
        } finally {
            long elapsed = elapsedMs(start);
            log.info("[PERF] {} completed in {} ms", operation, elapsed);
        }
    }
}
