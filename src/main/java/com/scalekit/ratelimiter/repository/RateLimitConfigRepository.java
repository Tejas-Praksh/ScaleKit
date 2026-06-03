package com.scalekit.ratelimiter.repository;

import com.scalekit.ratelimiter.domain.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for RateLimitConfig.
 */
@Repository
public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, Long> {

    /**
     * Find rate limit config rule by its key identifier.
     */
    Optional<RateLimitConfig> findByRuleKey(String ruleKey);
}
