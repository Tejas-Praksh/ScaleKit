package com.scalekit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a password-protected URL is accessed without a valid
 * password or temporary access token.
 *
 * <p>Maps to HTTP 401 Unauthorized so redirect clients know they must
 * supply credentials rather than interpret the response as "not found".
 */
public class UrlPasswordException extends ScaleKitException {

    public UrlPasswordException() {
        super("This URL is password-protected. Please provide the correct password.",
              "PASSWORD_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    public UrlPasswordException(String message) {
        super(message, "PASSWORD_REQUIRED", HttpStatus.UNAUTHORIZED);
    }
}
