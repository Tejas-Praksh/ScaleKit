package com.scalekit.ratelimiter.repository;

import com.scalekit.ratelimiter.domain.RateLimitViolation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for RateLimitViolation.
 */
@Repository
public interface RateLimitViolationRepository extends JpaRepository<RateLimitViolation, Long> {

    /**
     * Find rate limit violations/audit logs for a specific client/identifier.
     */
    List<RateLimitViolation> findByIdentifier(String identifier);
}
