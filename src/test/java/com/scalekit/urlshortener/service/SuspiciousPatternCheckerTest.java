package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.ThreatType;
import com.scalekit.urlshortener.service.impl.SuspiciousPatternChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousPatternCheckerTest {

    private SuspiciousPatternChecker suspiciousPatternChecker;

    @BeforeEach
    void setUp() {
        suspiciousPatternChecker = new SuspiciousPatternChecker();
    }

    @Test
    void checkPatterns_ipAddress_detected() {
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("http://192.168.1.1/path");
        assertThat(threats).contains(ThreatType.IP_ADDRESS_URL);

        List<ThreatType> threats2 = suspiciousPatternChecker.checkPatterns("https://[2001:db8::1]/index.html");
        assertThat(threats2).contains(ThreatType.IP_ADDRESS_URL);
    }

    @Test
    void checkPatterns_exeExtension_detected() {
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("http://example.com/download.exe");
        assertThat(threats).contains(ThreatType.MALWARE);

        List<ThreatType> threats2 = suspiciousPatternChecker.checkPatterns("http://example.com/malicious.zip");
        assertThat(threats2).contains(ThreatType.MALWARE);
    }

    @Test
    void checkPatterns_javascriptUri_blocked() {
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("javascript:alert(1)");
        assertThat(threats).contains(ThreatType.BLACKLISTED_DOMAIN);

        List<ThreatType> threats2 = suspiciousPatternChecker.checkPatterns("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==");
        assertThat(threats2).contains(ThreatType.BLACKLISTED_DOMAIN);
    }

    @Test
    void checkPatterns_normalUrl_noThreats() {
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("https://google.com/search?q=scalekit");
        assertThat(threats).isEmpty();
    }

    @Test
    void checkPatterns_longUrl_suspicious() {
        StringBuilder longUrl = new StringBuilder("http://example.com/path?");
        for (int i = 0; i < 2010; i++) {
            longUrl.append("a");
        }
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns(longUrl.toString());
        assertThat(threats).contains(ThreatType.SUSPICIOUS_PATH);
    }

    @Test
    void checkPatterns_multipleRedirects_detected() {
        // URL contains another URL as query parameter
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("http://example.com/redirect?url=https://evil.com/phish");
        assertThat(threats).contains(ThreatType.SUSPICIOUS_REDIRECT);
    }

    @Test
    void checkPatterns_misleadingSubdomain_detected() {
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("http://paypal.com.evil.com/login");
        assertThat(threats).contains(ThreatType.PHISHING);
    }

    @Test
    void checkPatterns_freeHosting_detected() {
        List<ThreatType> threats = suspiciousPatternChecker.checkPatterns("http://myblog.blogspot.com/post");
        assertThat(threats).contains(ThreatType.FREE_HOSTING);
    }
}
