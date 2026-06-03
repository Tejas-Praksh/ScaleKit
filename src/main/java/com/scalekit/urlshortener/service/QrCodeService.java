package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.QrCodeResponse;

/**
 * Service for generating and caching QR codes for shortened URLs.
 *
 * <p>QR codes are generated using ZXing (PNG, error correction HIGH)
 * and cached in Redis for 7 days — the code content never changes for
 * a given short URL, so long TTLs are appropriate.
 */
public interface QrCodeService {

    /**
     * Generates a QR code PNG image.
     *
     * @param content the text to encode (typically the full short URL)
     * @param size    pixel dimensions (width and height), must be 200–1000
     * @return PNG image bytes
     * @throws com.scalekit.common.exception.UrlException if generation fails
     */
    byte[] generateQrCode(String content, int size);

    /**
     * Generates a QR code and returns it as a Base64-encoded PNG string.
     *
     * @param content the text to encode
     * @param size    pixel dimensions, 200–1000
     * @return Base64-encoded PNG string
     */
    String generateQrCodeBase64(String content, int size);

    /**
     * Returns a cached QR code or generates one and caches it for 7 days.
     *
     * @param shortCode the short code (used as cache key component)
     * @param shortUrl  the full URL to encode into the QR code
     * @param size      pixel dimensions, 200–1000
     * @return full {@link QrCodeResponse} including Base64 image and metadata
     */
    QrCodeResponse getOrGenerateQrCode(String shortCode, String shortUrl, int size);
}
