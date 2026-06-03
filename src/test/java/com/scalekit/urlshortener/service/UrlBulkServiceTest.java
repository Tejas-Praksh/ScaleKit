package com.scalekit.urlshortener.service;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.exception.UrlException;
import com.scalekit.urlshortener.dto.*;
import com.scalekit.urlshortener.service.impl.UrlBulkServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlBulkServiceTest {

    @Mock
    private UrlService urlService;

    private UrlBulkService bulkService;

    @BeforeEach
    void setUp() {
        bulkService = new UrlBulkServiceImpl(urlService);
    }

    private CreateUrlRequest req(String url) {
        return CreateUrlRequest.builder().originalUrl(url).build();
    }

    private UrlResponse resp(String url, String code) {
        return UrlResponse.builder().originalUrl(url).shortCode(code).shortUrl("http://sk.io/" + code).build();
    }

    // ── All valid ──────────────────────────────────────────────────────────

    @Test
    void bulkCreate_allValid_returnsAllSuccess() {
        List<CreateUrlRequest> requests = List.of(
                req("https://one.com"),
                req("https://two.com"),
                req("https://three.com"));

        when(urlService.createUrl(requests.get(0))).thenReturn(resp("https://one.com", "aaa1111"));
        when(urlService.createUrl(requests.get(1))).thenReturn(resp("https://two.com", "bbb2222"));
        when(urlService.createUrl(requests.get(2))).thenReturn(resp("https://three.com", "ccc3333"));

        BulkCreateUrlRequest bulkRequest = BulkCreateUrlRequest.builder()
                .urls(requests)
                .failFast(false)
                .build();

        ApiResponse<BulkCreateUrlResponse> response = bulkService.bulkCreate(bulkRequest, null);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getTotalSuccessful()).isEqualTo(3);
        assertThat(response.getData().getTotalFailed()).isEqualTo(0);
        assertThat(response.getData().getSuccessful()).hasSize(3);
        assertThat(response.getData().getFailed()).isEmpty();
    }

    // ── Some invalid, failFast = false ─────────────────────────────────────

    @Test
    void bulkCreate_someInvalid_failFastFalse_continuesProcessing() {
        List<CreateUrlRequest> requests = List.of(
                req("https://ok1.com"),
                req("https://bad.com"),
                req("https://ok2.com"));

        when(urlService.createUrl(requests.get(0))).thenReturn(resp("https://ok1.com", "aaa1111"));
        when(urlService.createUrl(requests.get(1))).thenThrow(new UrlException("Duplicate alias"));
        when(urlService.createUrl(requests.get(2))).thenReturn(resp("https://ok2.com", "ccc3333"));

        BulkCreateUrlRequest bulkRequest = BulkCreateUrlRequest.builder()
                .urls(requests)
                .failFast(false)
                .build();

        ApiResponse<BulkCreateUrlResponse> response = bulkService.bulkCreate(bulkRequest, null);

        assertThat(response.getData().getTotalSuccessful()).isEqualTo(2);
        assertThat(response.getData().getTotalFailed()).isEqualTo(1);
        assertThat(response.getData().getFailed().get(0).getIndex()).isEqualTo(1);
        assertThat(response.getData().getFailed().get(0).getOriginalUrl()).isEqualTo("https://bad.com");

        // All 3 were attempted
        verify(urlService, times(3)).createUrl(any());
    }

    // ── Some invalid, failFast = true ──────────────────────────────────────

    @Test
    void bulkCreate_someInvalid_failFastTrue_stopsAtFirst() {
        List<CreateUrlRequest> requests = List.of(
                req("https://ok1.com"),
                req("https://bad.com"),
                req("https://ok2.com"));

        when(urlService.createUrl(requests.get(0))).thenReturn(resp("https://ok1.com", "aaa1111"));
        when(urlService.createUrl(requests.get(1))).thenThrow(new UrlException("Duplicate alias"));

        BulkCreateUrlRequest bulkRequest = BulkCreateUrlRequest.builder()
                .urls(requests)
                .failFast(true)
                .build();

        ApiResponse<BulkCreateUrlResponse> response = bulkService.bulkCreate(bulkRequest, null);

        assertThat(response.getData().getTotalSuccessful()).isEqualTo(1);
        assertThat(response.getData().getTotalFailed()).isEqualTo(1);

        // Third URL was never attempted because failFast=true stopped at index 1
        verify(urlService, times(2)).createUrl(any());
    }

    // ── Failed don't affect succeeded ──────────────────────────────────────

    @Test
    void bulkCreate_transactional_failedDontAffectSucceeded() {
        List<CreateUrlRequest> requests = List.of(
                req("https://good.com"),
                req("https://bad.com"));

        when(urlService.createUrl(requests.get(0))).thenReturn(resp("https://good.com", "ggg7777"));
        when(urlService.createUrl(requests.get(1))).thenThrow(new RuntimeException("Simulated DB error"));

        BulkCreateUrlRequest bulkRequest = BulkCreateUrlRequest.builder()
                .urls(requests)
                .failFast(false)
                .build();

        ApiResponse<BulkCreateUrlResponse> response = bulkService.bulkCreate(bulkRequest, null);

        // Successful URL is returned despite the failed one
        assertThat(response.getData().getSuccessful()).hasSize(1);
        assertThat(response.getData().getSuccessful().get(0).getShortCode()).isEqualTo("ggg7777");
    }

    // ── Processing time recorded ───────────────────────────────────────────

    @Test
    void processingTime_recorded_inResponse() {
        when(urlService.createUrl(any())).thenReturn(resp("https://example.com", "abc1234"));

        BulkCreateUrlRequest bulkRequest = BulkCreateUrlRequest.builder()
                .urls(List.of(req("https://example.com")))
                .build();

        ApiResponse<BulkCreateUrlResponse> response = bulkService.bulkCreate(bulkRequest, null);

        assertThat(response.getData().getProcessingTimeMs()).isGreaterThanOrEqualTo(0);
    }

    // ── Total counts ───────────────────────────────────────────────────────

    @Test
    void bulkCreate_counts_correctInResponse() {
        List<CreateUrlRequest> requests = List.of(
                req("https://ok.com"),
                req("https://fail.com"));

        when(urlService.createUrl(requests.get(0))).thenReturn(resp("https://ok.com", "abc1234"));
        when(urlService.createUrl(requests.get(1))).thenThrow(new UrlException("error"));

        ApiResponse<BulkCreateUrlResponse> response = bulkService.bulkCreate(
                BulkCreateUrlRequest.builder().urls(requests).build(), null);

        BulkCreateUrlResponse data = response.getData();
        assertThat(data.getTotalRequested()).isEqualTo(2);
        assertThat(data.getTotalSuccessful()).isEqualTo(1);
        assertThat(data.getTotalFailed()).isEqualTo(1);
    }
}
