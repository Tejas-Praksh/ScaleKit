package com.scalekit.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base runtime exception for all ScaleKit domain errors.
 *
 * <p>Carries an error code and HTTP status so the
 * {@link GlobalExceptionHandler} can produce correct responses
 * without instanceof checks.
 */
@Getter
public class ScaleKitException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;
    private final transient Object details;

    public ScaleKitException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = null;
    }

    public ScaleKitException(String message, String errorCode, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = null;
    }

    public ScaleKitException(String message, String errorCode, HttpStatus httpStatus, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = details;
    }
}
