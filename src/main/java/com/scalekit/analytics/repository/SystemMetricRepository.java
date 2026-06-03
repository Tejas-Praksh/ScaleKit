package com.scalekit.analytics.repository;

import com.scalekit.analytics.domain.SystemMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for SystemMetric.
 */
@Repository
public interface SystemMetricRepository extends JpaRepository<SystemMetric, Long> {

    /**
     * Find historical metrics for a specific subsystem, ordered by measurement time.
     */
    List<SystemMetric> findBySystemOrderByMeasuredAtDesc(String system);
}
