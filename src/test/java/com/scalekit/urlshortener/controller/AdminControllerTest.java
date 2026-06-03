package com.scalekit.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.config.RequestInterceptor;
import com.scalekit.urlshortener.domain.BlockedAttempt;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.dto.SafetyLevel;
import com.scalekit.urlshortener.repository.BlockedAttemptRepository;
import com.scalekit.urlshortener.service.UrlSafetyService;
import com.scalekit.urlshortener.service.impl.DomainBlacklistChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DomainBlacklistChecker domainBlacklistChecker;

    @MockBean
    private BlockedAttemptRepository blockedAttemptRepository;

    @MockBean
    private UrlSafetyService urlSafetyService;

    @MockBean
    private RequestInterceptor requestInterceptor;

    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockBean
    private com.scalekit.ratelimiter.service.RateLimiterService rateLimiterService;

    @MockBean
    private com.scalekit.ratelimiter.config.RateLimitRules rateLimitRules;

    @BeforeEach
    void setUp() {
        when(requestInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void addDomainToBlacklist_WithRequestParam_ShouldSucceed() throws Exception {
        mockMvc.perform(post("/api/v1/admin/blacklist")
                        .param("domain", "malicious.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Domain added to blacklist successfully"));

        verify(domainBlacklistChecker, times(1)).addDomain("malicious.com");
    }

    @Test
    void addDomainToBlacklist_WithRequestBody_ShouldSucceed() throws Exception {
        Map<String, String> body = Map.of("domain", "malicious.com");

        mockMvc.perform(post("/api/v1/admin/blacklist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Domain added to blacklist successfully"));

        verify(domainBlacklistChecker, times(1)).addDomain("malicious.com");
    }

    @Test
    void removeDomainFromBlacklist_ShouldSucceed() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/blacklist/{domain}", "malicious.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Domain removed from blacklist successfully"));

        verify(domainBlacklistChecker, times(1)).removeDomain("malicious.com");
    }

    @Test
    void getBlacklist_ShouldReturnDomains() throws Exception {
        when(domainBlacklistChecker.getBlacklistedDomains()).thenReturn(Set.of("malicious.com", "phishing.org"));

        mockMvc.perform(get("/api/v1/admin/blacklist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsInAnyOrder("malicious.com", "phishing.org")));
    }

    @Test
    void getBlockedAttempts_ShouldReturnPaginatedResults() throws Exception {
        BlockedAttempt attempt = BlockedAttempt.builder()
                .id(1L)
                .url("https://malware.com")
                .reputationScore(0)
                .threats("BLACKLISTED_DOMAIN")
                .blockedAt(Instant.now())
                .ipAddress("192.168.1.1")
                .userAgent("Mozilla")
                .build();

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "blockedAt"));
        when(blockedAttemptRepository.findAll(pageRequest)).thenReturn(new PageImpl<>(List.of(attempt)));

        mockMvc.perform(get("/api/v1/admin/blocked-attempts")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].url").value("https://malware.com"))
                .andExpect(jsonPath("$.data.content[0].ipAddress").value("192.168.1.1"));
    }

    @Test
    void bulkSafetyCheck_WithListBody_ShouldReturnResults() throws Exception {
        List<String> urls = List.of("http://test1.com", "http://test2.com");
        SafetyCheckResult result1 = SafetyCheckResult.builder().url(urls.get(0)).isSafe(true).reputationScore(100).safetyLevel(SafetyLevel.SAFE).build();
        SafetyCheckResult result2 = SafetyCheckResult.builder().url(urls.get(1)).isSafe(true).reputationScore(100).safetyLevel(SafetyLevel.SAFE).build();

        when(urlSafetyService.checkUrl(urls.get(0))).thenReturn(result1);
        when(urlSafetyService.checkUrl(urls.get(1))).thenReturn(result2);

        mockMvc.perform(post("/api/v1/admin/safety-check/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(urls)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].url").value("http://test1.com"))
                .andExpect(jsonPath("$.data[1].url").value("http://test2.com"));
    }

    @Test
    void bulkSafetyCheck_WithMapBody_ShouldReturnResults() throws Exception {
        Map<String, List<String>> body = Map.of("urls", List.of("http://test1.com"));
        SafetyCheckResult result1 = SafetyCheckResult.builder().url("http://test1.com").isSafe(true).reputationScore(100).safetyLevel(SafetyLevel.SAFE).build();

        when(urlSafetyService.checkUrl("http://test1.com")).thenReturn(result1);

        mockMvc.perform(post("/api/v1/admin/safety-check/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].url").value("http://test1.com"));
    }
}
