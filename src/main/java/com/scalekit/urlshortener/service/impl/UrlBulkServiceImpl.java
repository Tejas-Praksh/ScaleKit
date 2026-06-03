package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.util.PerformanceUtil;
import com.scalekit.urlshortener.dto.*;
import com.scalekit.urlshortener.service.UrlBulkService;
import com.scalekit.urlshortener.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link UrlBulkService}.
 *
 * <p>Each URL is processed with {@link Propagation#REQUIRES_NEW} to guarantee
 * independent transactions — a failed URL never rolls back a successful sibling.
 */
@Service
@RequiredArgsConstructor
public class UrlBulkServiceImpl implements UrlBulkService {

    private static final Logger log = LoggerFactory.getLogger(UrlBulkServiceImpl.class);

    private final UrlService urlService;

    @Override
    public ApiResponse<BulkCreateUrlResponse> bulkCreate(BulkCreateUrlRequest request, String createdBy) {
        long start = PerformanceUtil.startTimer();
        List<CreateUrlRequest> urls = request.getUrls();
        int total = urls.size();

        log.info("Starting bulk URL creation: {} URLs, failFast={}", total, request.isFailFast());

        List<UrlResponse> successful = new ArrayList<>();
        List<BulkUrlError> failed = new ArrayList<>();

        for (int i = 0; i < urls.size(); i++) {
            CreateUrlRequest urlRequest = urls.get(i);

            // Apply default createdBy if not set
            if (urlRequest.getCreatedBy() == null && createdBy != null) {
                urlRequest.setCreatedBy(createdBy);
            }

            Object result = processSingle(urlRequest, i, createdBy);

            if (result instanceof UrlResponse urlResponse) {
                successful.add(urlResponse);
            } else if (result instanceof BulkUrlError error) {
                failed.add(error);
                if (request.isFailFast()) {
                    log.info("failFast=true: stopping bulk processing at index {} due to error", i);
                    break;
                }
            }
        }

        long elapsed = PerformanceUtil.elapsedMs(start);
        log.info("Bulk URL creation complete: {}/{} succeeded, {} failed, {}ms",
                successful.size(), total, failed.size(), elapsed);

        BulkCreateUrlResponse response = BulkCreateUrlResponse.builder()
                .successful(successful)
                .failed(failed)
                .totalRequested(total)
                .totalSuccessful(successful.size())
                .totalFailed(failed.size())
                .processingTimeMs(elapsed)
                .build();

        return ApiResponse.success(response,
                "Bulk creation complete: " + successful.size() + " succeeded, " + failed.size() + " failed",
                elapsed);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object processSingle(CreateUrlRequest request, int index, String createdBy) {
        try {
            UrlResponse response = urlService.createUrl(request);
            log.debug("Bulk[{}]: created short code '{}'", index, response.getShortCode());
            return response;
        } catch (Exception e) {
            log.warn("Bulk[{}]: failed to create URL '{}': {}", index, request.getOriginalUrl(), e.getMessage());
            return BulkUrlError.builder()
                    .index(index)
                    .originalUrl(request.getOriginalUrl())
                    .errorCode(extractErrorCode(e))
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String extractErrorCode(Exception e) {
        // Try to extract ScaleKitException error code via reflection-free instanceof check
        if (e instanceof com.scalekit.common.exception.ScaleKitException ex) {
            return ex.getErrorCode();
        }
        if (e instanceof jakarta.validation.ConstraintViolationException) {
            return "VALIDATION_ERROR";
        }
        return "INTERNAL_ERROR";
    }
}
