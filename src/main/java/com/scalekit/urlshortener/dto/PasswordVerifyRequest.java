package com.scalekit.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for verifying access to a password-protected short URL.
 *
 * <p>The password is transmitted in plain text over TLS and verified
 * server-side against the BCrypt hash stored in the database.
 * It is never echoed back in any response.
 */
@Data
@NoArgsConstructor
public class PasswordVerifyRequest {

    @NotBlank(message = "Password must not be blank")
    private String password;
}
