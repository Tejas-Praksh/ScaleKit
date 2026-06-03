package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.ThreatType;
import com.scalekit.urlshortener.service.impl.TyposquattingDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TyposquattingDetectorTest {

    private TyposquattingDetector typosquattingDetector;

    @BeforeEach
    void setUp() {
        typosquattingDetector = new TyposquattingDetector();
    }

    @Test
    void levenshteinDistance_sameString_zero() {
        assertThat(typosquattingDetector.levenshteinDistance("google", "google")).isEqualTo(0);
    }

    @Test
    void levenshteinDistance_oneEdit_returnsOne() {
        // Insertion
        assertThat(typosquattingDetector.levenshteinDistance("google", "gooogle")).isEqualTo(1);
        // Deletion
        assertThat(typosquattingDetector.levenshteinDistance("google", "gogle")).isEqualTo(1);
        // Substitution
        assertThat(typosquattingDetector.levenshteinDistance("google", "go0gle")).isEqualTo(1);
    }

    @Test
    void levenshteinDistance_gooogle_google_one() {
        assertThat(typosquattingDetector.levenshteinDistance("gooogle", "google")).isEqualTo(1);
    }

    @Test
    void detectTyposquatting_gooogle_detected() {
        Optional<ThreatType> result = typosquattingDetector.detectTyposquatting("gooogle.com");
        assertThat(result).isPresent().contains(ThreatType.TYPOSQUATTING);
    }

    @Test
    void detectTyposquatting_google_notDetected() {
        Optional<ThreatType> result = typosquattingDetector.detectTyposquatting("google.com");
        assertThat(result).isEmpty();
    }

    @Test
    void detectTyposquatting_unrelatedDomain_notDetected() {
        Optional<ThreatType> result = typosquattingDetector.detectTyposquatting("mycleanwebsite.org");
        assertThat(result).isEmpty();
    }

    @Test
    void detectTyposquatting_distanceTwo_sameTld_detected() {
        // Distance 2 ("goooogle" vs "google"), same TLD ".com" -> detected
        Optional<ThreatType> result = typosquattingDetector.detectTyposquatting("goooogle.com");
        assertThat(result).isPresent().contains(ThreatType.TYPOSQUATTING);
    }

    @Test
    void detectTyposquatting_distanceTwo_differentTld_notDetected() {
        // Distance 2 ("goooogle" vs "google"), different TLD (".org" vs ".com") -> not detected
        Optional<ThreatType> result = typosquattingDetector.detectTyposquatting("goooogle.org");
        assertThat(result).isEmpty();
    }
}
