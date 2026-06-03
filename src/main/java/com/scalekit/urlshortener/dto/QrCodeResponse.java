package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response carrying the generated QR code for a short URL.
 *
 * <p>The QR code image is encoded as a Base64 PNG string for
 * direct embedding in HTML {@code <img>} tags or JSON APIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrCodeResponse {

    /** The short code this QR code resolves to. */
    private String shortCode;

    /**
     * The full short URL encoded in the QR code.
     * e.g. {@code https://sk.io/abc1234}
     */
    private String shortUrl;

    /** PNG image bytes encoded as a Base64 string. */
    private String qrCodeBase64;

    /** Pixel dimensions of the generated QR code (width = height). */
    private int size;

    /** Image format — always {@code "PNG"}. */
    @Builder.Default
    private String format = "PNG";

    /** When this QR code was generated. */
    private Instant generatedAt;
}
