package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.SafetyCheckResult;
import com.scalekit.urlshortener.dto.SafetyLevel;
import com.scalekit.urlshortener.dto.ThreatType;
import com.scalekit.urlshortener.repository.BlockedAttemptRepository;
import com.scalekit.urlshortener.service.impl.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlSafetyServiceTest {

    private UrlSafetyServiceImpl urlSafetyService;

    @Mock private DomainBlacklistChecker domainBlacklistChecker;
    @Mock private PhishingDetector phishingDetector;
    @Mock private TyposquattingDetector typosquattingDetector;
    @Mock private SuspiciousPatternChecker suspiciousPatternChecker;
    @Mock private IpAddressChecker ipAddressChecker;
    @Mock private UrlReputationCache urlReputationCache;
    @Mock private BlockedAttemptRepository blockedAttemptRepository;
    @Mock private MeterRegistry meterRegistry;

    @Mock private Counter checksTotalCounter;
    @Mock private Counter checksBlockedCounter;
    @Mock private Counter checksWarnedCounter;
    @Mock private Counter checksSafeCounter;
    @Mock private Counter cacheHitsCounter;
    @Mock private Timer checkDurationTimer;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUp() {
        lenient().when(meterRegistry.counter("scalekit.safety.checks.total")).thenReturn(checksTotalCounter);
        lenient().when(meterRegistry.counter("scalekit.safety.checks.blocked")).thenReturn(checksBlockedCounter);
        lenient().when(meterRegistry.counter("scalekit.safety.checks.warned")).thenReturn(checksWarnedCounter);
        lenient().when(meterRegistry.counter("scalekit.safety.checks.safe")).thenReturn(checksSafeCounter);
        lenient().when(meterRegistry.counter("scalekit.safety.cache.hits")).thenReturn(cacheHitsCounter);
        lenient().when(meterRegistry.timer("scalekit.safety.check.duration")).thenReturn(checkDurationTimer);

        lenient().doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(checkDurationTimer).record(any(Runnable.class));

        urlSafetyService = new UrlSafetyServiceImpl(
                domainBlacklistChecker,
                phishingDetector,
                typosquattingDetector,
                suspiciousPatternChecker,
                ipAddressChecker,
                urlReputationCache,
                blockedAttemptRepository,
                meterRegistry,
                executor
        );
    }

    @Test
    void checkUrl_safeDomain_returns100Score() {
        String url = "https://legitimate-domain.com/path";
        when(urlReputationCache.getCachedResult(url)).thenReturn(Optional.empty());
        when(domainBlacklistChecker.check(any())).thenReturn(Optional.empty());
        when(phishingDetector.detectPhishing(any(), any())).thenReturn(Collections.emptyList());
        when(typosquattingDetector.detectTyposquatting(any())).thenReturn(Optional.empty());
        when(suspiciousPatternChecker.checkPatterns(any())).thenReturn(Collections.emptyList());
        when(ipAddressChecker.check(any())).thenReturn(Optional.empty());

        SafetyCheckResult result = urlSafetyService.checkUrl(url);

        assertThat(result.getUrl()).isEqualTo(url);
        assertThat(result.isSafe()).isTrue();
        assertThat(result.getReputationScore()).isEqualTo(100);
        assertThat(result.getSafetyLevel()).isEqualTo(SafetyLevel.SAFE);
        assertThat(result.getThreats()).isEmpty();
        verify(urlReputationCache).cacheResult(eq(url), any());
    }

    @Test
    void checkUrl_blacklisted_returnsBlocked() {
        String url = "https://malware-test.com/path";
        when(urlReputationCache.getCachedResult(url)).thenReturn(Optional.empty());
        when(domainBlacklistChecker.check(any())).thenReturn(Optional.of(ThreatType.BLACKLISTED_DOMAIN));
        when(phishingDetector.detectPhishing(any(), any())).thenReturn(Collections.emptyList());
        when(typosquattingDetector.detectTyposquatting(any())).thenReturn(Optional.empty());
        when(suspiciousPatternChecker.checkPatterns(any())).thenReturn(Collections.emptyList());
        when(ipAddressChecker.check(any())).thenReturn(Optional.empty());

        SafetyCheckResult result = urlSafetyService.checkUrl(url);

        assertThat(result.isSafe()).isFalse();
        assertThat(result.getSafetyLevel()).isEqualTo(SafetyLevel.BLOCKED);
        assertThat(result.getReputationScore()).isEqualTo(0);
        assertThat(result.getThreats()).contains(ThreatType.BLACKLISTED_DOMAIN.name());
        verify(blockedAttemptRepository).save(any());
    }

    @Test
    void checkUrl_phishing_lowScore() {
        String url = "http://paypal-verify.com/login";
        when(urlReputationCache.getCachedResult(url)).thenReturn(Optional.empty());
        when(domainBlacklistChecker.check(any())).thenReturn(Optional.empty());
        when(phishingDetector.detectPhishing(any(), any())).thenReturn(List.of(ThreatType.PHISHING));
        when(typosquattingDetector.detectTyposquatting(any())).thenReturn(Optional.empty());
        when(suspiciousPatternChecker.checkPatterns(any())).thenReturn(Collections.emptyList());
        when(ipAddressChecker.check(any())).thenReturn(Optional.empty());

        SafetyCheckResult result = urlSafetyService.checkUrl(url);

        assertThat(result.isSafe()).isFalse();
        assertThat(result.getReputationScore()).isEqualTo(60);
        assertThat(result.getSafetyLevel()).isEqualTo(SafetyLevel.WARNING);
    }

    @Test
    void checkUrl_cacheHit_returnsCached() {
        String url = "https://google.com";
        SafetyCheckResult cachedResult = SafetyCheckResult.builder()
                .url(url)
                .isSafe(true)
                .reputationScore(100)
                .safetyLevel(SafetyLevel.SAFE)
                .fromCache(true)
                .build();
        when(urlReputationCache.getCachedResult(url)).thenReturn(Optional.of(cachedResult));

        SafetyCheckResult result = urlSafetyService.checkUrl(url);

        assertThat(result.isFromCache()).isTrue();
        assertThat(result.getReputationScore()).isEqualTo(100);
        verifyNoInteractions(domainBlacklistChecker);
    }

    @Test
    void calculateScore_multipleThreats_deductsAll() {
        int score = urlSafetyService.calculateScore(List.of(ThreatType.PHISHING, ThreatType.TYPOSQUATTING));
        assertThat(score).isEqualTo(40);
    }

    @Test
    void calculateScore_neverBelowZero() {
        int score = urlSafetyService.calculateScore(List.of(ThreatType.MALWARE, ThreatType.PHISHING, ThreatType.TYPOSQUATTING));
        assertThat(score).isEqualTo(0);
    }
}
