package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.service.impl.CounterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CounterServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CounterServiceImpl counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterServiceImpl(stringRedisTemplate, jdbcTemplate);
    }

    @Test
    void getNextId_WhenRedisIsUp_ShouldReturnIncrementedValue() {
        // Arrange
        String key = "url:counter";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(1000000050L);

        // Act
        long result = counterService.getNextId();

        // Assert
        assertEquals(1000000050L, result);
        verify(valueOperations, times(1)).increment(key);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void getNextId_WhenRedisIsDown_ShouldFallbackToDatabaseSequence() {
        // Arrange
        String key = "url:counter";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenThrow(new RedisSystemException("Redis Connection Failure", new RuntimeException()));
        when(jdbcTemplate.queryForObject(eq("SELECT nextval('url_sequence')"), eq(Long.class))).thenReturn(1000000099L);

        // Act
        long result = counterService.getNextId();

        // Assert
        assertEquals(1000000099L, result);
        verify(valueOperations, times(1)).increment(key);
        verify(jdbcTemplate, times(1)).queryForObject(eq("SELECT nextval('url_sequence')"), eq(Long.class));
    }

    @Test
    void getNextId_WhenBothRedisAndDbSequenceAreDown_ShouldThrowIllegalStateException() {
        // Arrange
        String key = "url:counter";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenThrow(new RuntimeException("Redis error"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenThrow(new RuntimeException("DB error"));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> counterService.getNextId());
    }
}
