package com.scalekit.urlshortener.service;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.urlshortener.dto.BulkCreateUrlRequest;
import com.scalekit.urlshortener.dto.BulkCreateUrlResponse;
import com.scalekit.urlshortener.dto.BulkUrlError;
import com.scalekit.urlshortener.dto.CreateUrlRequest;
import com.scalekit.urlshortener.dto.UrlResponse;

/**
 * Service for processing multiple URL shortening requests in a single call.
 *
 * <p>Each URL is treated as an independent transaction. Failures in one
 * URL do not roll back previously successful ones. The caller controls
 * whether processing stops on the first error via the {@code failFast} flag.
 */
public interface UrlBulkService {

    /**
     * Creates multiple shortened URLs in a single operation.
     *
     * @param request    the bulk request containing up to 100 URLs
     * @param createdBy  optional attribution identifier (user/API key)
     * @return a response envelope with successes, failures, counts, and timing
     */
    ApiResponse<BulkCreateUrlResponse> bulkCreate(BulkCreateUrlRequest request, String createdBy);

    /**
     * Attempts to create a single URL and returns either a success or an error record.
     *
     * <p>Never throws — exceptions are caught and converted into {@link BulkUrlError}.
     *
     * @param request   the individual URL creation request
     * @param index     zero-based position in the original list (for error reporting)
     * @param createdBy optional attribution identifier
     * @return the created {@link UrlResponse} or a {@link BulkUrlError} on failure
     */
    Object processSingle(CreateUrlRequest request, int index, String createdBy);
}
