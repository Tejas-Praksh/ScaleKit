package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.util.IpUtil;
import com.scalekit.urlshortener.domain.BlockedAttempt;
import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.dto.SafetyLevel;
import com.scalekit.urlshortener.dto.ThreatType;
import com.scalekit.urlshortener.repository.BlockedAttemptRepository;
import com.scalekit.urlshortener.service.UrlReputationCache;
import com.scalekit.urlshortener.service.UrlSafetyService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Implementation of the URL safety service checking urls for malware, phishing, typosquatting, blacklist domains, etc.
 */
@Service
public class UrlSafetyServiceImpl implements UrlSafetyService {

    private static final Logger log = LoggerFactory.getLogger(UrlSafetyServiceImpl.class);

    private final DomainBlacklistChecker domainBlacklistChecker;
    private final PhishingDetector phishingDetector;
    private final TyposquattingDetector typosquattingDetector;
    private final SuspiciousPatternChecker suspiciousPatternChecker;
    private final IpAddressChecker ipAddressChecker;
    private final UrlReputationCache urlReputationCache;
    private final BlockedAttemptRepository blockedAttemptRepository;
    private final MeterRegistry meterRegistry;
    private final Executor executor;

    public UrlSafetyServiceImpl(
            DomainBlacklistChecker domainBlacklistChecker,
            PhishingDetector phishingDetector,
            TyposquattingDetector typosquattingDetector,
            SuspiciousPatternChecker suspiciousPatternChecker,
            IpAddressChecker ipAddressChecker,
            UrlReputationCache urlReputationCache,
            BlockedAttemptRepository blockedAttemptRepository,
            MeterRegistry meterRegistry,
            @Qualifier("analyticsExecutor") Executor executor) {
        this.domainBlacklistChecker = domainBlacklistChecker;
        this.phishingDetector = phishingDetector;
        this.typosquattingDetector = typosquattingDetector;
        this.suspiciousPatternChecker = suspiciousPatternChecker;
        this.ipAddressChecker = ipAddressChecker;
        this.urlReputationCache = urlReputationCache;
        this.blockedAttemptRepository = blockedAttemptRepository;
        this.meterRegistry = meterRegistry;
        this.executor = executor;
    }

    @Override
    public SafetyCheckResult checkUrl(String url) {
        if (url == null) {
            return null;
        }

        // 1. Check Cache
        Optional<SafetyCheckResult> cached = urlReputationCache.getCachedResult(url);
        if (cached.isPresent()) {
            meterRegistry.counter("scalekit.safety.cache.hits").increment();
            meterRegistry.counter("scalekit.safety.checks.total").increment();
            return cached.get();
        }

        // 2. Perform evaluation inside Timer metrics
        SafetyCheckResult[] resultHolder = new SafetyCheckResult[1];
        meterRegistry.timer("scalekit.safety.check.duration").record(() -> {
            resultHolder[0] = performEvaluation(url);
        });

        SafetyCheckResult result = resultHolder[0];

        // 3. Increment counters based on SafetyLevel
        meterRegistry.counter("scalekit.safety.checks.total").increment();
        if (result.getSafetyLevel() == SafetyLevel.SAFE) {
            meterRegistry.counter("scalekit.safety.checks.safe").increment();
        } else if (result.getSafetyLevel() == SafetyLevel.WARNING) {
            meterRegistry.counter("scalekit.safety.checks.warned").increment();
        } else if (result.getSafetyLevel() == SafetyLevel.DANGEROUS || result.getSafetyLevel() == SafetyLevel.BLOCKED) {
            meterRegistry.counter("scalekit.safety.checks.blocked").increment();
        }

        // 4. Cache evaluation result
        urlReputationCache.cacheResult(url, result);

        // 5. Database logging for blocked attempts
        if (result.getSafetyLevel() == SafetyLevel.DANGEROUS || result.getSafetyLevel() == SafetyLevel.BLOCKED) {
            logBlockedAttempt(result);
        }

        return result;
    }

    private SafetyCheckResult performEvaluation(String url) {
        String domain = extractDomain(url);

        // Run checker components in parallel using analyticsExecutor
        CompletableFuture<Optional<ThreatType>> blacklistFuture = CompletableFuture.supplyAsync(
                () -> domainBlacklistChecker.check(domain), executor);

        CompletableFuture<List<ThreatType>> phishingFuture = CompletableFuture.supplyAsync(
                () -> phishingDetector.detectPhishing(url, domain), executor);

        CompletableFuture<Optional<ThreatType>> typosquattingFuture = CompletableFuture.supplyAsync(
                () -> typosquattingDetector.detectTyposquatting(domain), executor);

        CompletableFuture<List<ThreatType>> suspiciousFuture = CompletableFuture.supplyAsync(
                () -> suspiciousPatternChecker.checkPatterns(url), executor);

        CompletableFuture<Optional<ThreatType>> ipFuture = CompletableFuture.supplyAsync(
                () -> ipAddressChecker.check(url), executor);

        // Join on all checking futures
        try {
            CompletableFuture.allOf(blacklistFuture, phishingFuture, typosquattingFuture, suspiciousFuture, ipFuture).join();
        } catch (Exception e) {
            log.error("Failed executing parallel url safety checks: {}", e.getMessage(), e);
        }

        Optional<ThreatType> blacklistThreat = blacklistFuture.getNow(Optional.empty());
        List<ThreatType> phishingThreats = phishingFuture.getNow(Collections.emptyList());
        Optional<ThreatType> typosquattingThreat = typosquattingFuture.getNow(Optional.empty());
        List<ThreatType> suspiciousThreats = suspiciousFuture.getNow(Collections.emptyList());
        Optional<ThreatType> ipThreat = ipFuture.getNow(Optional.empty());

        // Aggregate threats
        List<ThreatType> allThreats = new ArrayList<>();
        blacklistThreat.ifPresent(allThreats::add);
        allThreats.addAll(phishingThreats);
        typosquattingThreat.ifPresent(allThreats::add);
        allThreats.addAll(suspiciousThreats);
        ipThreat.ifPresent(allThreats::add);

        // Calculate reputation score and levels
        int reputationScore;
        SafetyLevel safetyLevel;

        if (blacklistThreat.isPresent() && blacklistThreat.get() == ThreatType.BLACKLISTED_DOMAIN) {
            reputationScore = 0;
            safetyLevel = SafetyLevel.BLOCKED;
        } else {
            reputationScore = calculateScore(allThreats);
            safetyLevel = SafetyLevel.fromScore(reputationScore);
        }

        boolean isSafe = (safetyLevel == SafetyLevel.SAFE);

        List<String> threatsList = allThreats.stream()
                .map(Enum::name)
                .distinct()
                .collect(Collectors.toList());

        List<String> warnings = new ArrayList<>();
        String recommendation = "No action required.";
        if (safetyLevel == SafetyLevel.WARNING) {
            warnings.add("URL has suspicious elements. Proceed with caution.");
            recommendation = "Review URL components carefully before redirection.";
        } else if (safetyLevel == SafetyLevel.DANGEROUS) {
            warnings.add("URL contains characteristics of malware, phishing, or other malicious entities.");
            recommendation = "Shortening blocked due to reputation scoring.";
        } else if (safetyLevel == SafetyLevel.BLOCKED) {
            warnings.add("URL is explicitly blocked as a known threat.");
            recommendation = "Request denied.";
        }

        return SafetyCheckResult.builder()
                .url(url)
                .isSafe(isSafe)
                .reputationScore(reputationScore)
                .safetyLevel(safetyLevel)
                .threats(threatsList)
                .warnings(warnings)
                .recommendation(recommendation)
                .checkedAt(Instant.now())
                .fromCache(false)
                .build();
    }

    public int calculateScore(List<ThreatType> threats) {
        if (threats == null || threats.isEmpty()) {
            return 100;
        }
        int score = 100;
        for (ThreatType threat : threats) {
            switch (threat) {
                case BLACKLISTED_DOMAIN:
                    score -= 100;
                    break;
                case MALWARE:
                    score -= 50;
                    break;
                case PHISHING:
                    score -= 40;
                    break;
                case TYPOSQUATTING:
                    score -= 20;
                    break;
                case SUSPICIOUS_PATH:
                    score -= 15;
                    break;
                case IP_ADDRESS_URL:
                    score -= 10;
                    break;
                case EXCESSIVE_SUBDOMAINS:
                    score -= 10;
                    break;
                case FREE_HOSTING:
                    score -= 5;
                    break;
                case CREDENTIAL_HARVESTING:
                    score -= 30;
                    break;
                case SUSPICIOUS_REDIRECT:
                    score -= 15;
                    break;
                case SPAM:
                    score -= 15;
                    break;
                case URL_SHORTENER:
                    score -= 10;
                    break;
                default:
                    break;
            }
        }
        return Math.max(0, score);
    }

    private String extractDomain(String url) {
        if (url == null) {
            return "";
        }
        String lowerUrl = url.toLowerCase().trim();
        String host = null;
        try {
            URI uri;
            if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
                uri = new URI(url);
            } else {
                uri = new URI("http://" + url);
            }
            host = uri.getHost();
        } catch (Exception e) {
            int protoIndex = lowerUrl.indexOf("://");
            int hostStart = (protoIndex != -1) ? protoIndex + 3 : 0;
            int slashIndex = lowerUrl.indexOf("/", hostStart);
            if (slashIndex != -1) {
                host = lowerUrl.substring(hostStart, slashIndex);
            } else {
                host = lowerUrl.substring(hostStart);
            }
        }
        if (host != null) {
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return host;
        }
        return "";
    }

    private void logBlockedAttempt(SafetyCheckResult result) {
        String ipAddress = null;
        String userAgent = null;

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            if (request != null) {
                ipAddress = IpUtil.extractClientIp(request);
                userAgent = request.getHeader("User-Agent");
            }
        }

        String threatsStr = result.getThreats() != null ? String.join(",", result.getThreats()) : "";

        BlockedAttempt attempt = BlockedAttempt.builder()
                .url(result.getUrl())
                .reputationScore(result.getReputationScore())
                .threats(threatsStr)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();

        try {
            blockedAttemptRepository.save(attempt);
        } catch (Exception e) {
            log.error("Failed to save blocked attempt for URL: {}", result.getUrl(), e);
        }
    }
}
