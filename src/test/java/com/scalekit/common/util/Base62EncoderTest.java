package com.scalekit.common.util;

import com.scalekit.common.constants.SystemConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Base62Encoder")
class Base62EncoderTest {

    @Test
    @DisplayName("encode(0) returns all zeros")
    void encode_zero_returnsAllZeros() {
        String result = Base62Encoder.encode(0);
        assertEquals("0000000", result);
        assertEquals(SystemConstants.URL_SHORT_CODE_LENGTH, result.length());
    }

    @Test
    @DisplayName("encode(positive) returns correct Base62 code")
    void encode_positive_returnsCorrectCode() {
        String result = Base62Encoder.encode(1000);
        assertNotNull(result);
        assertEquals(SystemConstants.URL_SHORT_CODE_LENGTH, result.length());
        // 1000 in base62: 16*62 + 8 = "G8", padded to 7 chars
        assertTrue(result.endsWith("G8"));
    }

    @Test
    @DisplayName("decode(encoded) returns original number")
    void decode_encodedValue_returnsOriginal() {
        String encoded = Base62Encoder.encode(123456789L);
        long decoded = Base62Encoder.decode(encoded);
        assertEquals(123456789L, decoded);
    }

    @Test
    @DisplayName("encode → decode round-trip preserves value")
    void encode_decode_roundTrip() {
        long[] testValues = {0, 1, 61, 62, 100, 999999, 123456789L, Long.MAX_VALUE / 1000};
        for (long value : testValues) {
            String encoded = Base62Encoder.encode(value);
            long decoded = Base62Encoder.decode(encoded);
            assertEquals(value, decoded, "Round-trip failed for: " + value);
        }
    }

    @Test
    @DisplayName("generateShortCode() returns 7-character string")
    void generateShortCode_returnsSevenChars() {
        String code = Base62Encoder.generateShortCode();
        assertEquals(SystemConstants.URL_SHORT_CODE_LENGTH, code.length());
    }

    @Test
    @DisplayName("generateShortCode() uses only Base62 characters")
    void generateShortCode_onlyBase62Chars() {
        for (int i = 0; i < 100; i++) {
            String code = Base62Encoder.generateShortCode();
            for (char c : code.toCharArray()) {
                assertTrue(SystemConstants.BASE62_CHARS.indexOf(c) >= 0,
                        "Invalid character found: " + c);
            }
        }
    }

    @Test
    @DisplayName("generateShortCode() produces unique values")
    void generateShortCode_uniqueValues() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(Base62Encoder.generateShortCode());
        }
        assertEquals(1000, codes.size(), "Expected 1000 unique codes");
    }

    @Test
    @DisplayName("isValidShortCode() returns true for valid code")
    void isValidShortCode_valid_returnsTrue() {
        assertTrue(Base62Encoder.isValidShortCode("abc1234"));
        assertTrue(Base62Encoder.isValidShortCode("AAAAAAA"));
        assertTrue(Base62Encoder.isValidShortCode("0000000"));
    }

    @Test
    @DisplayName("isValidShortCode() returns false for wrong length")
    void isValidShortCode_tooShort_returnsFalse() {
        assertFalse(Base62Encoder.isValidShortCode("abc"));
        assertFalse(Base62Encoder.isValidShortCode("abcdefgh"));
        assertFalse(Base62Encoder.isValidShortCode(""));
        assertFalse(Base62Encoder.isValidShortCode(null));
    }

    @Test
    @DisplayName("isValidShortCode() returns false for special characters")
    void isValidShortCode_specialChars_returnsFalse() {
        assertFalse(Base62Encoder.isValidShortCode("abc-123"));
        assertFalse(Base62Encoder.isValidShortCode("abc_123"));
        assertFalse(Base62Encoder.isValidShortCode("abc 123"));
    }

    @Test
    @DisplayName("encode(negative) throws IllegalArgumentException")
    void encode_negative_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.encode(-1));
    }
}
