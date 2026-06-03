package com.scalekit.common.util;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hash utility providing multiple hash functions for Bloom filters
 * and consistent hashing.
 *
 * <p>MD5 and SHA-256 use JDK {@link MessageDigest}.
 * MurmurHash3 delegates to Guava. FNV-1a is implemented from scratch.
 */
public final class HashUtil {

    private static final long FNV_OFFSET_BASIS_32 = 2166136261L;
    private static final long FNV_PRIME_32 = 16777619L;

    private HashUtil() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    /**
     * Computes the MD5 hex digest of the input string.
     */
    public static String md5(String input) {
        return hexDigest("MD5", input);
    }

    /**
     * Computes the SHA-256 hex digest of the input string.
     */
    public static String sha256(String input) {
        return hexDigest("SHA-256", input);
    }

    /**
     * Computes a 32-bit MurmurHash3 using Guava.
     *
     * @param input the string to hash
     * @param seed  hash seed for independent hash functions
     * @return 32-bit hash value
     */
    @SuppressWarnings("deprecation")
    public static int murmur3(String input, int seed) {
        return Hashing.murmur3_32_fixed(seed)
                .hashString(input, StandardCharsets.UTF_8)
                .asInt();
    }

    /**
     * Computes a 32-bit FNV-1a hash.
     *
     * <p>Implemented from scratch following the FNV-1a specification.
     *
     * @param input the string to hash
     * @return 32-bit FNV-1a hash value
     */
    public static int fnv1a(String input) {
        long hash = FNV_OFFSET_BASIS_32;
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME_32;
            hash &= 0xFFFFFFFFL; // keep within 32-bit range
        }
        return (int) hash;
    }

    private static String hexDigest(String algorithm, String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm not available: " + algorithm, e);
        }
    }
}
