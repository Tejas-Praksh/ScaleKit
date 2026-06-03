package com.scalekit.urlshortener.repository;

import com.scalekit.urlshortener.domain.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data JPA repository for URL persistent operations.
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

    /**
     * Find URL record by its base62 short code.
     */
    Optional<Url> findByShortCode(String shortCode);

    /**
     * Find URL record by its custom alias.
     */
    Optional<Url> findByCustomAlias(String customAlias);

    /**
     * Increments click count of a URL atomically.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Url u SET u.clickCount = u.clickCount + 1, u.lastAccessedAt = :now WHERE u.shortCode = :shortCode")
    int incrementClickCount(String shortCode, Instant now);

    /**
     * Increments both click count and unique click count of a URL atomically.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Url u SET u.clickCount = u.clickCount + 1, u.uniqueClickCount = u.uniqueClickCount + 1, u.lastAccessedAt = :now WHERE u.shortCode = :shortCode")
    int incrementClickAndUniqueCount(String shortCode, Instant now);
}

