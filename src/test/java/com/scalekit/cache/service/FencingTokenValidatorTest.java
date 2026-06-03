package com.scalekit.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FencingTokenValidatorTest {

    private FencingTokenValidator validator;
    private String resource;

    @BeforeEach
    void setUp() {
        validator = new FencingTokenValidator();
        resource = "db:table:row-42";
    }

    @Test
    void validate_firstToken_accepted() {
        assertTrue(validator.validate(resource, 100L));
    }

    @Test
    void validate_higherToken_accepted() {
        assertTrue(validator.validate(resource, 100L));
        assertTrue(validator.validate(resource, 101L));
    }

    @Test
    void validate_lowerToken_rejected() {
        assertTrue(validator.validate(resource, 100L));
        assertFalse(validator.validate(resource, 99L));
    }

    @Test
    void validate_sameToken_rejected() {
        assertTrue(validator.validate(resource, 100L));
        assertFalse(validator.validate(resource, 100L));
    }

    @Test
    void raceCondition_withFencing_prevented() {
        // Simulate Thread 1 (stale lock holder, token = 33) and Thread 2 (current lock holder, token = 34)
        long thread1Token = 33L;
        long thread2Token = 34L;

        // Thread 2 acquires lock and writes first
        assertTrue(validator.validate(resource, thread2Token));

        // Thread 1 (which was paused due to GC) resumes and tries to write
        boolean thread1WriteSuccess = validator.validate(resource, thread1Token);

        // Assert: Thread 1's write is rejected because the token is stale (33 < 34)
        assertFalse(thread1WriteSuccess);
    }
}
