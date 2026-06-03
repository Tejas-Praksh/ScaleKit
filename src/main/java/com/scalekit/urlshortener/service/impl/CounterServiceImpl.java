package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.constants.SystemConstants;
import com.scalekit.urlshortener.service.CounterService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Thread-safe implementation of {@link CounterService} designed for extreme high throughput.
 *
 * <p>Uses Redis atomic increment key {@code url:counter} as the primary fast generator.
 * If Redis is unavailable or times out, it gracefully degrades to a database sequence
 * {@code url_sequence} via JDBC, ensuring zero service downtime.
 */
@Service
public class CounterServiceImpl implements CounterService {

    private static final Logger log = LoggerFactory.getLogger(CounterServiceImpl.class);
    private static final String COUNTER_KEY = SystemConstants.REDIS_URL_PREFIX + "counter";

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public CounterServiceImpl(
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate,
            JdbcTemplate jdbcTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing fallback database sequence 'url_sequence'...");
            jdbcTemplate.execute(
                    "CREATE SEQUENCE IF NOT EXISTS url_sequence START WITH 1000000000 INCREMENT BY 1;"
            );
            log.info("Fallback database sequence 'url_sequence' initialized successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize database sequence 'url_sequence'", e);
        }
    }

    @Override
    public long getNextId() {
        if (stringRedisTemplate != null) {
            try {
                Long nextId = stringRedisTemplate.opsForValue().increment(COUNTER_KEY);
                if (nextId != null) {
                    return nextId;
                }
            } catch (Exception e) {
                log.warn("Redis counter unavailable, falling back to database sequence. Reason: {}", e.getMessage());
            }
        }

        // Degradation path: Query sequence from DB
        try {
            Long nextId = jdbcTemplate.queryForObject("SELECT nextval('url_sequence')", Long.class);
            if (nextId != null) {
                return nextId;
            }
        } catch (Exception e) {
            log.error("Critical fallback database sequence lookup failed!", e);
            throw new IllegalStateException("Unable to generate unique ID: both Redis and Database sequence are unavailable", e);
        }

        throw new IllegalStateException("Generated ID is null");
    }
}
