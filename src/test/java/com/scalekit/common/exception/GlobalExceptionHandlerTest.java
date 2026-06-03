package com.scalekit.common.exception;

import com.scalekit.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/test");
    }

    @Test
    @DisplayName("RateLimitExceededException returns 429")
    void rateLimitExceeded_returns429() {
        RateLimitExceededException ex = new RateLimitExceededException("user-123", 60);

        ResponseEntity<ApiResponse<Object>> response = handler.handleScaleKitException(ex, request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("RATE_LIMIT_EXCEEDED", response.getBody().getErrorCode());
    }

    @Test
    @DisplayName("ResourceNotFoundException returns 404")
    void resourceNotFound_returns404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("URL", "abc1234");

        ResponseEntity<ApiResponse<Object>> response = handler.handleScaleKitException(ex, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().getErrorCode());
        assertTrue(response.getBody().getMessage().contains("abc1234"));
    }

    @Test
    @DisplayName("IllegalArgumentException returns 400")
    void illegalArgument_returns400() {
        IllegalArgumentException ex = new IllegalArgumentException("Bad input");

        ResponseEntity<ApiResponse<Object>> response = handler.handleIllegalArgument(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("BAD_REQUEST", response.getBody().getErrorCode());
    }

    @Test
    @DisplayName("Unknown exception returns 500 without exposing details")
    void unknownError_returns500NoDetails() {
        Exception ex = new RuntimeException("Database connection lost");

        ResponseEntity<ApiResponse<Object>> response = handler.handleGeneral(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("INTERNAL_ERROR", response.getBody().getErrorCode());
        assertFalse(response.getBody().getMessage().contains("Database"),
                "Internal error details should not be exposed");
    }
}
