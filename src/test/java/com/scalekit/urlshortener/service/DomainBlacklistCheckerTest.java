package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.ThreatType;
import com.scalekit.urlshortener.service.impl.DomainBlacklistChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DomainBlacklistCheckerTest {

    private DomainBlacklistChecker domainBlacklistChecker;

    @BeforeEach
    void setUp() {
        domainBlacklistChecker = new DomainBlacklistChecker();
        domainBlacklistChecker.loadBlacklist();
    }

    @Test
    void check_blacklistedDomain_returnsThreat() {
        Optional<ThreatType> result = domainBlacklistChecker.check("malware-test.com");
        assertThat(result).isPresent().contains(ThreatType.BLACKLISTED_DOMAIN);
    }

    @Test
    void check_cleanDomain_returnsEmpty() {
        Optional<ThreatType> result = domainBlacklistChecker.check("google.com");
        assertThat(result).isEmpty();
    }

    @Test
    void check_subdomainOfBlacklisted_detected() {
        Optional<ThreatType> result = domainBlacklistChecker.check("sub.malware-test.com");
        assertThat(result).isPresent().contains(ThreatType.BLACKLISTED_DOMAIN);

        Optional<ThreatType> deepResult = domainBlacklistChecker.check("evil.sub.malware-test.com");
        assertThat(deepResult).isPresent().contains(ThreatType.BLACKLISTED_DOMAIN);
    }

    @Test
    void loadBlacklist_loadsFromFile() {
        // blacklist.txt contains: malware-example.com, phishing-test.net, spam-domain.org, fake-paypal.com
        Optional<ThreatType> result = domainBlacklistChecker.check("malware-example.com");
        assertThat(result).isPresent().contains(ThreatType.BLACKLISTED_DOMAIN);

        Optional<ThreatType> result2 = domainBlacklistChecker.check("phishing-test.net");
        assertThat(result2).isPresent().contains(ThreatType.BLACKLISTED_DOMAIN);
    }

    @Test
    void isShortenerDomain_returnsTrue_forShorteners() {
        assertThat(domainBlacklistChecker.isShortenerDomain("bit.ly")).isTrue();
        assertThat(domainBlacklistChecker.isShortenerDomain("tinyurl.com")).isTrue();
        assertThat(domainBlacklistChecker.isShortenerDomain("google.com")).isFalse();
    }
}
