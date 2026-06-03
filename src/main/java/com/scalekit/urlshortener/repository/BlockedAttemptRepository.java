package com.scalekit.urlshortener.repository;

import com.scalekit.urlshortener.domain.BlockedAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for managing {@link BlockedAttempt} entities.
 */
@Repository
public interface BlockedAttemptRepository extends JpaRepository<BlockedAttempt, Long> {
}
