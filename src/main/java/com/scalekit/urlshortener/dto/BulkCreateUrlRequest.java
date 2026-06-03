package com.scalekit.urlshortener.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for creating multiple shortened URLs in a single API call.
 *
 * <p>Supports both fail-fast (stop on first error) and best-effort
 * (continue and collect all errors) processing modes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkCreateUrlRequest {

    @Valid
    @NotEmpty(message = "URL list must not be empty")
    @Size(max = 100, message = "Cannot process more than 100 URLs per request")
    private List<CreateUrlRequest> urls;

    /**
     * Optional default expiry in days applied to all URLs that don't specify their own.
     * Accepts 1–365.
     */
    private String defaultExpiryDays;

    /**
     * When {@code true}, processing stops at the first failure.
     * When {@code false} (default), all URLs are attempted and errors are collected.
     */
    @Builder.Default
    private boolean failFast = false;
}
