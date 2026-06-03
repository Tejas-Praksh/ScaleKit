package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single URL that failed during bulk creation.
 *
 * <p>Carries enough context for the caller to understand exactly
 * which input failed and why, without parsing exception messages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUrlError {

    /** Zero-based index of the failed URL in the original request list. */
    private int index;

    /** The original URL that was being shortened when the error occurred. */
    private String originalUrl;

    /** Machine-readable error code (e.g. {@code URL_ERROR}, {@code VALIDATION_ERROR}). */
    private String errorCode;

    /** Human-readable description of the failure. */
    private String errorMessage;
}
