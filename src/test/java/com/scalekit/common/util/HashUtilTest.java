package com.scalekit.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HashUtil")
class HashUtilTest {

    @Test
    @DisplayName("MD5: same input produces same output")
    void md5_sameInput_sameOutput() {
        String hash1 = HashUtil.md5("hello");
        String hash2 = HashUtil.md5("hello");
        assertEquals(hash1, hash2);
        assertEquals(32, hash1.length()); // MD5 = 128 bits = 32 hex chars
    }

    @Test
    @DisplayName("SHA-256: different inputs produce different outputs")
    void sha256_differentInputs_differentOutputs() {
        String hash1 = HashUtil.sha256("hello");
        String hash2 = HashUtil.sha256("world");
        assertNotEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 = 256 bits = 64 hex chars
    }

    @Test
    @DisplayName("MurmurHash3: same input+seed returns consistent result")
    void murmur3_withSeed_returnsConsistent() {
        int hash1 = HashUtil.murmur3("test-key", 42);
        int hash2 = HashUtil.murmur3("test-key", 42);
        assertEquals(hash1, hash2);

        // Different seeds should (very likely) produce different hashes
        int hash3 = HashUtil.murmur3("test-key", 99);
        assertNotEquals(hash1, hash3);
    }

    @Test
    @DisplayName("FNV-1a: known values produce correct output")
    void fnv1a_knownValues_correctOutput() {
        // FNV-1a is deterministic
        int hash1 = HashUtil.fnv1a("hello");
        int hash2 = HashUtil.fnv1a("hello");
        assertEquals(hash1, hash2);

        // Different inputs should produce different hashes
        int hash3 = HashUtil.fnv1a("world");
        assertNotEquals(hash1, hash3);

        // Verify known FNV-1a value for empty string
        int emptyHash = HashUtil.fnv1a("");
        assertEquals((int) 2166136261L, emptyHash, "FNV-1a of empty string should be the offset basis");
    }

    @Test
    @DisplayName("MD5: known hash value for 'hello'")
    void md5_knownValue() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtil.md5("hello"));
    }

    @Test
    @DisplayName("SHA-256: known hash value for 'hello'")
    void sha256_knownValue() {
        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                HashUtil.sha256("hello"));
    }
}
