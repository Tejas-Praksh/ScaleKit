package com.scalekit.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested resource cannot be found. Returns HTTP 404.
 */
public class ResourceNotFoundException extends ScaleKitException {

    public ResourceNotFoundException(String resource, String id) {
        super(resource + " not found: " + id, "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    /** Single-message constructor for flexible use in service layer. */
    public ResourceNotFoundException(String message) {
        super(message, "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
