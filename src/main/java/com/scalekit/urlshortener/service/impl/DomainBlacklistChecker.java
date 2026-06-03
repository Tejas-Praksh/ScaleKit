package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.dto.ThreatType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checker component to verify if a domain is blacklisted or belongs to another URL shortener.
 */
@Component
public class DomainBlacklistChecker {

    private static final Logger log = LoggerFactory.getLogger(DomainBlacklistChecker.class);

    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    private final Set<String> shorteners = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void loadBlacklist() {
        // Pre-populate with example domains
        Collections.addAll(blacklist,
                "malware-test.com",
                "phishing-example.com",
                "bit.ly",
                "tinyurl.com"
        );

        Collections.addAll(shorteners,
                "bit.ly",
                "tinyurl.com"
        );

        // Load from classpath file
        try {
            ClassPathResource resource = new ClassPathResource("security/blacklist.txt");
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        blacklist.add(line.toLowerCase());
                        count++;
                    }
                    log.info("Loaded {} blacklisted domains from blacklist.txt", count);
                }
            } else {
                log.warn("blacklist.txt not found in classpath. Using default memory-only blacklist.");
            }
        } catch (Exception e) {
            log.error("Failed to load domains from blacklist.txt: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks if the given domain (or its parent domains) is blacklisted.
     */
    public Optional<ThreatType> check(String domain) {
        if (domain == null) {
            return Optional.empty();
        }
        String lowerDomain = domain.toLowerCase().trim();

        // Exact match
        if (blacklist.contains(lowerDomain)) {
            if (shorteners.contains(lowerDomain)) {
                return Optional.of(ThreatType.URL_SHORTENER);
            }
            return Optional.of(ThreatType.BLACKLISTED_DOMAIN);
        }

        // Subdomain checks
        for (String blacklisted : blacklist) {
            if (lowerDomain.endsWith("." + blacklisted)) {
                if (shorteners.contains(blacklisted)) {
                    return Optional.of(ThreatType.URL_SHORTENER);
                }
                return Optional.of(ThreatType.BLACKLISTED_DOMAIN);
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if a domain is a known URL shortener domain.
     */
    public boolean isShortenerDomain(String domain) {
        if (domain == null) {
            return false;
        }
        String lowerDomain = domain.toLowerCase().trim();
        if (shorteners.contains(lowerDomain)) {
            return true;
        }
        for (String shortener : shorteners) {
            if (lowerDomain.endsWith("." + shortener)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runtime addition to blacklist.
     */
    public void addDomain(String domain) {
        if (domain != null) {
            blacklist.add(domain.toLowerCase().trim());
        }
    }

    /**
     * Runtime removal from blacklist.
     */
    public void removeDomain(String domain) {
        if (domain != null) {
            blacklist.remove(domain.toLowerCase().trim());
        }
    }

    /**
     * Retrieves all blacklisted domains (for admin display).
     */
    public Set<String> getBlacklistedDomains() {
        return Collections.unmodifiableSet(blacklist);
    }
}
