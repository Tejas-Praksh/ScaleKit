package com.scalekit.urlshortener.repository;

import com.scalekit.urlshortener.domain.UrlAnalytics;
import com.scalekit.urlshortener.domain.UrlAnalyticsId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for UrlAnalytics partitioned entities.
 */
@Repository
public interface UrlAnalyticsRepository extends JpaRepository<UrlAnalytics, UrlAnalyticsId> {

    /**
     * Retrieves analytics records for a given short code.
     */
    List<UrlAnalytics> findByShortCode(String shortCode);

    /**
     * Counts the total clicks for a given short code.
     */
    long countByShortCode(String shortCode);

    /**
     * Checks if an analytics record exists for a short code and IP address combination.
     */
    boolean existsByShortCodeAndIpAddress(String shortCode, String ipAddress);

    /**
     * Retrieves analytics records created after a specific timestamp.
     */
    List<UrlAnalytics> findByClickedAtAfter(Instant clickedAt);

    /**
     * Deletes analytics records created before a specific timestamp.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM UrlAnalytics u WHERE u.clickedAt < :threshold")
    @org.springframework.transaction.annotation.Transactional
    void deleteByClickedAtBefore(Instant threshold);
}

