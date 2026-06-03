package com.scalekit.urlshortener.repository;

import com.scalekit.urlshortener.domain.UrlDailyStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for UrlDailyStats.
 */
@Repository
public interface UrlDailyStatsRepository extends JpaRepository<UrlDailyStats, Long> {

    /**
     * Find daily statistics by short code and date.
     */
    Optional<UrlDailyStats> findByShortCodeAndDate(String shortCode, LocalDate date);

    /**
     * Get all historical stats for a given short code.
     */
    List<UrlDailyStats> findByShortCode(String shortCode);
}
