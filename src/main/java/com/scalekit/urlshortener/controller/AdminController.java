package com.scalekit.urlshortener.controller;

import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.dto.PagedResponse;
import com.scalekit.common.util.PerformanceUtil;
import com.scalekit.urlshortener.domain.BlockedAttempt;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.repository.BlockedAttemptRepository;
import com.scalekit.urlshortener.service.UrlSafetyService;
import com.scalekit.urlshortener.service.impl.DomainBlacklistChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for administrative safety operations, including domain blacklist management,
 * blocked attempt audit logs, and bulk safety scans.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DomainBlacklistChecker domainBlacklistChecker;
    private final BlockedAttemptRepository blockedAttemptRepository;
    private final UrlSafetyService urlSafetyService;

    public AdminController(
            DomainBlacklistChecker domainBlacklistChecker,
            BlockedAttemptRepository blockedAttemptRepository,
            UrlSafetyService urlSafetyService) {
        this.domainBlacklistChecker = domainBlacklistChecker;
        this.blockedAttemptRepository = blockedAttemptRepository;
        this.urlSafetyService = urlSafetyService;
    }

    /**
     * Adds a domain to the safety blacklist.
     */
    @PostMapping("/blacklist")
    public ResponseEntity<ApiResponse<Void>> addDomainToBlacklist(
            @RequestParam(required = false) String domain,
            @RequestBody(required = false) Map<String, String> body) {
        String finalDomain = domain;
        if (finalDomain == null && body != null) {
            finalDomain = body.get("domain");
        }
        if (finalDomain == null || finalDomain.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Domain parameter is required", "BAD_REQUEST"));
        }
        domainBlacklistChecker.addDomain(finalDomain);
        return ResponseEntity.ok(ApiResponse.success(null, "Domain added to blacklist successfully"));
    }

    /**
     * Removes a domain from the safety blacklist.
     */
    @DeleteMapping("/blacklist/{domain}")
    public ResponseEntity<ApiResponse<Void>> removeDomainFromBlacklist(@PathVariable String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Domain is required", "BAD_REQUEST"));
        }
        domainBlacklistChecker.removeDomain(domain);
        return ResponseEntity.ok(ApiResponse.success(null, "Domain removed from blacklist successfully"));
    }

    /**
     * Retrieves the current safety blacklist.
     */
    @GetMapping("/blacklist")
    public ResponseEntity<ApiResponse<Set<String>>> getBlacklist() {
        return ResponseEntity.ok(ApiResponse.success(
                domainBlacklistChecker.getBlacklistedDomains(),
                "Blacklisted domains retrieved successfully"));
    }

    /**
     * Retrieves the paginated audit logs of blocked URL shortening attempts.
     */
    @GetMapping("/blocked-attempts")
    public ResponseEntity<ApiResponse<PagedResponse<BlockedAttempt>>> getBlockedAttempts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        long start = PerformanceUtil.startTimer();
        Page<BlockedAttempt> pagedResult = blockedAttemptRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "blockedAt")));
        PagedResponse<BlockedAttempt> response = PagedResponse.of(pagedResult, PerformanceUtil.elapsedMs(start));
        return ResponseEntity.ok(ApiResponse.success(response, "Blocked attempts retrieved successfully"));
    }

    /**
     * Runs bulk safety checks on a list of URLs.
     */
    @PostMapping("/safety-check/bulk")
    public ResponseEntity<ApiResponse<List<SafetyCheckResult>>> bulkSafetyCheck(
            @RequestBody Object body) {
        List<String> urls = new ArrayList<>();
        if (body instanceof List) {
            List<?> rawList = (List<?>) body;
            for (Object obj : rawList) {
                if (obj != null) {
                    urls.add(obj.toString());
                }
            }
        } else if (body instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) body;
            Object urlsObj = map.get("urls");
            if (urlsObj instanceof List) {
                List<?> rawList = (List<?>) urlsObj;
                for (Object obj : rawList) {
                    if (obj != null) {
                        urls.add(obj.toString());
                    }
                }
            }
        }

        long start = PerformanceUtil.startTimer();
        List<SafetyCheckResult> results = new ArrayList<>();
        for (String url : urls) {
            results.add(urlSafetyService.checkUrl(url));
        }
        long elapsed = PerformanceUtil.elapsedMs(start);
        return ResponseEntity.ok(ApiResponse.success(results, "Bulk safety check completed successfully", elapsed));
    }
}
