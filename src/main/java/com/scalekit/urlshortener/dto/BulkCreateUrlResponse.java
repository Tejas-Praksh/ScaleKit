package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for a bulk URL creation operation.
 *
 * <p>Reports every successful URL alongside a detailed error list for
 * failed items so callers can handle partial success.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkCreateUrlResponse {

    /** Successfully created URLs in the same order as the input list. */
    private List<UrlResponse> successful;

    /** Failed items with per-item error details. */
    private List<BulkUrlError> failed;

    /** Total number of URLs in the original request. */
    private int totalRequested;

    /** Number of successfully created URLs. */
    private int totalSuccessful;

    /** Number of failed URLs. */
    private int totalFailed;

    /** Wall-clock processing time for the entire operation in milliseconds. */
    private long processingTimeMs;
}
