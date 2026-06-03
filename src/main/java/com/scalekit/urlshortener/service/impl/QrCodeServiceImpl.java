package com.scalekit.urlshortener.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.scalekit.common.exception.UrlException;
import com.scalekit.urlshortener.dto.QrCodeResponse;
import com.scalekit.urlshortener.service.QrCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * ZXing-based implementation of {@link QrCodeService}.
 *
 * <p>Generates PNG QR codes with HIGH error correction level.
 * Results are serialised as JSON and cached in Redis for 7 days.
 */
@Service
public class QrCodeServiceImpl implements QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeServiceImpl.class);

    private static final String QR_CACHE_PREFIX = "url:qr:";
    private static final Duration QR_CACHE_TTL = Duration.ofDays(7);
    private static final int MIN_SIZE = 200;
    private static final int MAX_SIZE = 1000;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public QrCodeServiceImpl(
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] generateQrCode(String content, int size) {
        int clampedSize = Math.min(Math.max(size, MIN_SIZE), MAX_SIZE);

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, clampedSize, clampedSize, hints);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos);
            return baos.toByteArray();
        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR code for '{}': {}", content, e.getMessage());
            throw new UrlException("Failed to generate QR code: " + e.getMessage());
        }
    }

    @Override
    public String generateQrCodeBase64(String content, int size) {
        byte[] pngBytes = generateQrCode(content, size);
        return Base64.getEncoder().encodeToString(pngBytes);
    }

    @Override
    public QrCodeResponse getOrGenerateQrCode(String shortCode, String shortUrl, int size) {
        int clampedSize = Math.min(Math.max(size, MIN_SIZE), MAX_SIZE);
        String cacheKey = QR_CACHE_PREFIX + shortCode + ":" + clampedSize;

        // 1. Cache read
        if (stringRedisTemplate != null) {
            try {
                String cached = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    log.debug("QR cache HIT for '{}' size={}", shortCode, clampedSize);
                    return objectMapper.readValue(cached, QrCodeResponse.class);
                }
            } catch (Exception e) {
                log.warn("QR cache read failed for '{}': {}", shortCode, e.getMessage());
            }
        }

        // 2. Generate
        log.debug("QR cache MISS for '{}' size={}, generating...", shortCode, clampedSize);
        String base64 = generateQrCodeBase64(shortUrl, clampedSize);

        QrCodeResponse response = QrCodeResponse.builder()
                .shortCode(shortCode)
                .shortUrl(shortUrl)
                .qrCodeBase64(base64)
                .size(clampedSize)
                .format("PNG")
                .generatedAt(Instant.now())
                .build();

        // 3. Cache for 7 days
        if (stringRedisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(response);
                stringRedisTemplate.opsForValue().set(cacheKey, json, QR_CACHE_TTL);
            } catch (Exception e) {
                log.warn("QR cache write failed for '{}': {}", shortCode, e.getMessage());
            }
        }

        return response;
    }
}
