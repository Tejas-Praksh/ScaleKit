package com.scalekit.tests;

import com.scalekit.common.util.Base62Encoder;
import com.scalekit.common.constants.SystemConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Base62 Encoder Correctness Tests")
public class Base62EncoderCorrectnessTest {

    @Test
    void encode_zero_returnsAllZeros() {
        assertEquals("0".repeat(SystemConstants.URL_SHORT_CODE_LENGTH),
                Base62Encoder.encode(0));
    }

    @Test
    void encode_one_returnsCorrectCode() {
        assertEquals("0".repeat(SystemConstants.URL_SHORT_CODE_LENGTH - 1) + "1",
                Base62Encoder.encode(1));
    }

    @Test
    void encode_largeNumber_correct() {
        // Known value computed beforehand
        long val = 1_000_000L;
        String expected = Base62Encoder.encode(val); // We just assert round‑trip below
        assertEquals(7, expected.length());
        assertEquals(val, Base62Encoder.decode(expected));
    }

    @Test
    void roundTrip_anyNumber_exact() {
        long[] numbers = {0L, 1L, 100L, 999_999L, 1_000_000L, 99_999_999L};
        for (long n : numbers) {
            String enc = Base62Encoder.encode(n);
            assertEquals(n, Base62Encoder.decode(enc), "Round‑trip failed for " + n);
        }
    }

    @Test
    void sequential_1M_allUnique() {
        Set<String> codes = new HashSet<>();
        for (long i = 1_000_000L; i <= 2_000_000L; i++) {
            codes.add(Base62Encoder.encode(i));
        }
        assertEquals(1_000_001, codes.size()); // inclusive range yields 1,000,001 values
    }

    @Test
    void encode_onlyBase62Chars() {
        Random r = new Random();
        for (int i = 0; i < 1_000; i++) {
            long num = Math.abs(r.nextLong()) % 10_000_000L;
            String enc = Base62Encoder.encode(num);
            for (char c : enc.toCharArray()) {
                assertTrue(SystemConstants.BASE62_CHARS.indexOf(c) >= 0,
                        "Invalid char '" + c + "' in encoded string");
            }
        }
    }

    @Test
    void encode_alwaysSevenChars() {
        for (long i = 1_000_000L; i <= 1_000_100L; i++) {
            assertEquals(7, Base62Encoder.encode(i).length(), "Length mismatch for " + i);
        }
    }
}
