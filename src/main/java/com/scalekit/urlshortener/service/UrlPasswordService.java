package com.scalekit.urlshortener.service;

/**
 * Service for managing password protection on shortened URLs.
 *
 * <p>All password hashing uses BCrypt with strength 10.
 * Plain-text passwords are never stored, logged, or echoed.
 * Temporary access tokens allow clients to avoid re-entering
 * passwords on every redirect within a session window.
 */
public interface UrlPasswordService {

    /**
     * Hashes a plain-text password using BCrypt (strength 10).
     *
     * @param plainPassword the user-supplied password
     * @return BCrypt hash safe to store in the database
     */
    String hashPassword(String plainPassword);

    /**
     * Verifies a plain-text password against a stored BCrypt hash.
     *
     * <p>Uses constant-time comparison to prevent timing attacks.
     *
     * @param plainPassword  the user-supplied password
     * @param hashedPassword the BCrypt hash from the database
     * @return {@code true} if the password matches
     */
    boolean verifyPassword(String plainPassword, String hashedPassword);

    /**
     * Generates a short-lived JWT access token for a password-protected URL.
     *
     * <p>The token is stored in Redis with a 1-hour TTL. Successful verification
     * allows the client to redirect without re-supplying the password.
     *
     * @param shortCode the short code to issue the token for
     * @return a signed JWT string
     */
    String generateTempAccessToken(String shortCode);

    /**
     * Validates a temporary access token for a given short code.
     *
     * @param shortCode the short code the token was issued for
     * @param token     the JWT string to validate
     * @return {@code true} if the token is valid and has not expired
     */
    boolean validateTempToken(String shortCode, String token);
}
