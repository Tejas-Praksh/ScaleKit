package com.scalekit.common.util;

import com.scalekit.common.constants.SystemConstants;

import java.security.SecureRandom;

/**
 * Base62 encoder/decoder for generating compact, URL-safe short codes.
 *
 * <p>Implemented from scratch — no external library dependency for core logic.
 * Uses the character set {@code 0-9A-Za-z} (62 characters).
 */
public final class Base62Encoder {

    private static final String CHARS = SystemConstants.BASE62_CHARS;
    private static final int BASE = CHARS.length();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Base62Encoder() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    /**
     * Encodes a non-negative number into a Base62 string,
     * padded to {@link SystemConstants#URL_SHORT_CODE_LENGTH} characters.
     *
     * @param number non-negative number to encode
     * @return Base62-encoded string, left-padded with '0'
     * @throws IllegalArgumentException if number is negative
     */
    public static String encode(long number) {
        if (number < 0) {
            throw new IllegalArgumentException("Number must be non-negative: " + number);
        }
        if (number == 0) {
            return "0".repeat(SystemConstants.URL_SHORT_CODE_LENGTH);
        }

        StringBuilder sb = new StringBuilder();
        long current = number;
        while (current > 0) {
            sb.append(CHARS.charAt((int) (current % BASE)));
            current /= BASE;
        }
        sb.reverse();

        // Left-pad with '0' to reach desired length
        while (sb.length() < SystemConstants.URL_SHORT_CODE_LENGTH) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    /**
     * Decodes a Base62-encoded string back to a number.
     *
     * @param encoded Base62 string
     * @return the decoded number
     * @throws IllegalArgumentException if string contains invalid characters
     */
    public static long decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Encoded string must not be null or empty");
        }

        long result = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int index = CHARS.indexOf(encoded.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException(
                        "Invalid Base62 character: '" + encoded.charAt(i) + "'");
            }
            result = result * BASE + index;
        }
        return result;
    }

    /**
     * Generates a cryptographically random 7-character Base62 short code.
     *
     * @return random short code
     */
    public static String generateShortCode() {
        StringBuilder sb = new StringBuilder(SystemConstants.URL_SHORT_CODE_LENGTH);
        for (int i = 0; i < SystemConstants.URL_SHORT_CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(SECURE_RANDOM.nextInt(BASE)));
        }
        return sb.toString();
    }

    /**
     * Validates that a string is a legal Base62 short code.
     *
     * @param code the code to validate
     * @return {@code true} if valid
     */
    public static boolean isValidShortCode(String code) {
        if (code == null || code.length() != SystemConstants.URL_SHORT_CODE_LENGTH) {
            return false;
        }
        for (char c : code.toCharArray()) {
            if (CHARS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }
}
