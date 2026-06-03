package com.scalekit.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

import java.time.Instant;

/**
 * Request DTO for creating a new shortened URL.
 *
 * <p>Validation enforces URL format, alias character set, and length limits.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUrlRequest {

    @NotBlank(message = "Original URL is required")
    @URL(message = "Must be a valid URL")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    private String originalUrl;

    /**
     * Optional vanity alias (e.g. "my-brand"). Must be alphanumeric, hyphens, underscores.
     * Checked for uniqueness before creation.
     */
    @Size(max = 20, message = "Custom alias must not exceed 20 characters")
    @Pattern(
            regexp = "^[a-zA-Z0-9\\-_]*$",
            message = "Custom alias may only contain letters, digits, hyphens, and underscores"
    )
    private String customAlias;

    /** Optional expiry timestamp. Expired URLs return HTTP 410 Gone. */
    private Instant expiresAt;

    @Size(max = 255)
    private String createdBy;

    @Size(max = 500)
    private String title;

    @Size(max = 1000)
    private String description;
}
