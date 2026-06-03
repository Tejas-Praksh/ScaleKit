package com.scalekit.common.exception;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.util.CorrelationIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler providing consistent error responses across all controllers.
 *
 * <p>Every handler logs with the correlation ID, returns {@link ApiResponse#error},
 * and includes the requestId from MDC.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ScaleKitException.class)
    public ResponseEntity<ApiResponse<Object>> handleScaleKitException(
            ScaleKitException ex, HttpServletRequest request) {
        log.warn("[{}] ScaleKitException at {}: {} (code={})",
                CorrelationIdUtil.get(), request.getRequestURI(), ex.getMessage(), ex.getErrorCode());

        ApiResponse<Object> response;
        if (ex.getDetails() != null) {
            response = ApiResponse.error(ex.getMessage(), ex.getErrorCode(), ex.getDetails());
        } else {
            response = ApiResponse.error(ex.getMessage(), ex.getErrorCode());
        }
        response.setRequestId(CorrelationIdUtil.get());
        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("[{}] Validation error at {}", CorrelationIdUtil.get(), request.getRequestURI());

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ApiResponse<Object> response = ApiResponse.error(
                "Validation failed", "VALIDATION_ERROR", fieldErrors);
        response.setRequestId(CorrelationIdUtil.get());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        log.warn("[{}] Constraint violation at {}", CorrelationIdUtil.get(), request.getRequestURI());

        String violations = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));

        ApiResponse<Object> response = ApiResponse.error(
                "Constraint violation: " + violations, "VALIDATION_ERROR");
        response.setRequestId(CorrelationIdUtil.get());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("[{}] Illegal argument at {}: {}",
                CorrelationIdUtil.get(), request.getRequestURI(), ex.getMessage());

        ApiResponse<Object> response = ApiResponse.error(
                ex.getMessage(), "BAD_REQUEST");
        response.setRequestId(CorrelationIdUtil.get());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneral(
            Exception ex, HttpServletRequest request) {
        log.error("[{}] Unhandled exception at {}: {}",
                CorrelationIdUtil.get(), request.getRequestURI(), ex.getMessage(), ex);

        ApiResponse<Object> response = ApiResponse.error(
                "An unexpected error occurred", "INTERNAL_ERROR");
        response.setRequestId(CorrelationIdUtil.get());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
