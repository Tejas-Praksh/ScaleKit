package com.scalekit.urlshortener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scalekit.urlshortener.dto.QrCodeResponse;
import com.scalekit.urlshortener.service.impl.QrCodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class QrCodeServiceTest {

    private QrCodeService qrCodeService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // No Redis in unit tests
        qrCodeService = new QrCodeServiceImpl(null, objectMapper);
    }

    // ── generateQrCode ─────────────────────────────────────────────────────

    @Test
    void generateQrCode_validUrl_returnsByteArray() {
        byte[] bytes = qrCodeService.generateQrCode("https://sk.io/abc1234", 200);
        assertThat(bytes).isNotNull().isNotEmpty();
    }

    @Test
    void generateQrCode_outputIsPng() {
        byte[] bytes = qrCodeService.generateQrCode("https://sk.io/test", 200);
        // PNG magic bytes: 0x89 0x50 0x4E 0x47
        assertThat(bytes[0]).isEqualTo((byte) 0x89);
        assertThat(bytes[1]).isEqualTo((byte) 0x50); // 'P'
        assertThat(bytes[2]).isEqualTo((byte) 0x4E); // 'N'
        assertThat(bytes[3]).isEqualTo((byte) 0x47); // 'G'
    }

    @Test
    void generateQrCode_size200_producesSmallerOutput() {
        byte[] small = qrCodeService.generateQrCode("https://sk.io/abc", 200);
        byte[] large = qrCodeService.generateQrCode("https://sk.io/abc", 500);
        // Larger QR = larger file
        assertThat(large.length).isGreaterThan(small.length);
    }

    // ── generateQrCodeBase64 ───────────────────────────────────────────────

    @Test
    void generateQrCodeBase64_validBase64() {
        String base64 = qrCodeService.generateQrCodeBase64("https://sk.io/abc1234", 200);
        assertThat(base64).isNotBlank();

        // Must be valid Base64 — decode should not throw
        assertThatNoException().isThrownBy(() -> {
            byte[] decoded = Base64.getDecoder().decode(base64);
            assertThat(decoded).isNotEmpty();
        });
    }

    @Test
    void generateQrCodeBase64_decodedIsPng() {
        String base64 = qrCodeService.generateQrCodeBase64("https://sk.io/test", 200);
        byte[] decoded = Base64.getDecoder().decode(base64);

        // Verify PNG magic bytes
        assertThat(decoded[0]).isEqualTo((byte) 0x89);
        assertThat(decoded[1]).isEqualTo((byte) 0x50);
    }

    // ── getOrGenerateQrCode ────────────────────────────────────────────────

    @Test
    void getOrGenerateQrCode_cacheMiss_generatesValidResponse() {
        QrCodeResponse response = qrCodeService.getOrGenerateQrCode(
                "abc1234", "https://sk.io/abc1234", 200);

        assertThat(response).isNotNull();
        assertThat(response.getShortCode()).isEqualTo("abc1234");
        assertThat(response.getShortUrl()).isEqualTo("https://sk.io/abc1234");
        assertThat(response.getQrCodeBase64()).isNotBlank();
        assertThat(response.getSize()).isEqualTo(200);
        assertThat(response.getFormat()).isEqualTo("PNG");
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    void getOrGenerateQrCode_sizeClamped_min200() {
        QrCodeResponse response = qrCodeService.getOrGenerateQrCode(
                "abc1234", "https://sk.io/abc1234", 50); // below min
        assertThat(response.getSize()).isEqualTo(200);
    }

    @Test
    void getOrGenerateQrCode_sizeClamped_max1000() {
        QrCodeResponse response = qrCodeService.getOrGenerateQrCode(
                "abc1234", "https://sk.io/abc1234", 2000); // above max
        assertThat(response.getSize()).isEqualTo(1000);
    }
}
