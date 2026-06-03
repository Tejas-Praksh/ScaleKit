package com.scalekit.urlshortener.dto;

import lombok.Getter;

/**
 * Types of threats that can be detected by the safety scanner.
 */
@Getter
public enum ThreatType {
    PHISHING("Potential phishing attempt"),
    MALWARE("Known malware distribution"),
    SPAM("Spam or unwanted content"),
    TYPOSQUATTING("Possible typosquatting"),
    SUSPICIOUS_REDIRECT("Suspicious redirect chain"),
    BLACKLISTED_DOMAIN("Domain is blacklisted"),
    SUSPICIOUS_PATH("Suspicious URL path"),
    CREDENTIAL_HARVESTING("Possible credential harvesting"),
    IP_ADDRESS_URL("URL uses raw IP address"),
    EXCESSIVE_SUBDOMAINS("Suspicious subdomain structure"),
    URL_SHORTENER("Another URL shortener"),
    FREE_HOSTING("Free hosting service");

    private final String description;

    ThreatType(String description) {
        this.description = description;
    }
}
