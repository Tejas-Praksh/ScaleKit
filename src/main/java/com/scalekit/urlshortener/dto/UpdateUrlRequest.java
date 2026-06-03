package com.scalekit.urlshortener.dto;

import jakarta.validation.constraints.Future;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Request DTO for partially updating an existing shortened URL.
 * All fields are optional — only non-null values are applied.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUrlRequest {

    /** Optional new display title. */
    private String title;

    /**
     * Optional new custom alias. Must be unique if provided.
     * Constraints enforced at service level (3-20 chars, alphanumeric).
     */
    private String customAlias;

    /** Optional new expiry timestamp. Must be in the future. */
    @Future(message = "Expiry date must be in the future")
    private Instant expiresAt;

    /** Activate or deactivate the URL. */
    private Boolean isActive;

    /** Enable or disable click analytics tracking. */
    private Boolean trackAnalytics;
}
