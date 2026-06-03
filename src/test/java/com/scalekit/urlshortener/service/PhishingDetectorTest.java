package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.ThreatType;
import com.scalekit.urlshortener.service.impl.PhishingDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhishingDetectorTest {

    private PhishingDetector phishingDetector;

    @BeforeEach
    void setUp() {
        phishingDetector = new PhishingDetector();
        phishingDetector.loadProtectedBrands();
    }

    @Test
    void detectPhishing_brandInNonBrandDomain_detected() {
        // Domain has brand in path or as subdomain, but domain is not the brand
        List<ThreatType> threats = phishingDetector.detectPhishing("http://evil.com/paypal/verify", "evil.com");
        assertThat(threats).contains(ThreatType.PHISHING);

        List<ThreatType> threats2 = phishingDetector.detectPhishing("http://paypal-verification.com", "paypal-verification.com");
        assertThat(threats2).contains(ThreatType.PHISHING);
    }

    @Test
    void detectPhishing_correctBrandDomain_notDetected() {
        // Legitimate brand domain should not trigger phishing
        List<ThreatType> threats = phishingDetector.detectPhishing("https://paypal.com/signin", "paypal.com");
        assertThat(threats).doesNotContain(ThreatType.PHISHING);

        List<ThreatType> threats2 = phishingDetector.detectPhishing("https://api.paypal.com/v1/payments", "api.paypal.com");
        assertThat(threats2).doesNotContain(ThreatType.PHISHING);
    }

    @Test
    void detectPhishing_suspiciousKeywords_detected() {
        // Path contains 3+ keywords -> CREDENTIAL_HARVESTING
        List<ThreatType> threats = phishingDetector.detectPhishing("http://clean-domain.com/login/verify/confirm", "clean-domain.com");
        assertThat(threats).contains(ThreatType.CREDENTIAL_HARVESTING);
    }

    @Test
    void detectPhishing_normalUrl_noThreats() {
        List<ThreatType> threats = phishingDetector.detectPhishing("https://google.com/search?q=scalekit", "google.com");
        assertThat(threats).isEmpty();
    }

    @Test
    void detectPhishing_homograph_detected() {
        // Homograph domains using look-alike chars
        List<ThreatType> threats = phishingDetector.detectPhishing("http://paypa1.com", "paypa1.com");
        assertThat(threats).contains(ThreatType.PHISHING);

        List<ThreatType> threats2 = phishingDetector.detectPhishing("http://arnazon.com", "arnazon.com");
        assertThat(threats2).contains(ThreatType.PHISHING);

        List<ThreatType> threats3 = phishingDetector.detectPhishing("http://g00gle.com", "g00gle.com");
        assertThat(threats3).contains(ThreatType.PHISHING);
    }

    @Test
    void detectPhishing_excessiveSpecialChars_detected() {
        // Domain with more than 3 special chars (@, %, -, _) -> PHISHING
        List<ThreatType> threats = phishingDetector.detectPhishing("http://my-secure-login-portal-verify.com", "my-secure-login-portal-verify.com");
        assertThat(threats).contains(ThreatType.PHISHING);
    }

    @Test
    void detectPhishing_excessiveSubdomains_detected() {
        // More than 3 subdomains -> EXCESSIVE_SUBDOMAINS
        List<ThreatType> threats = phishingDetector.detectPhishing("http://sub1.sub2.sub3.sub4.example.com", "sub1.sub2.sub3.sub4.example.com");
        assertThat(threats).contains(ThreatType.EXCESSIVE_SUBDOMAINS);
    }
}
