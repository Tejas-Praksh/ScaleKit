package com.scalekit.analytics.repository;

import com.scalekit.analytics.domain.AlgorithmBenchmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for AlgorithmBenchmark.
 */
@Repository
public interface AlgorithmBenchmarkRepository extends JpaRepository<AlgorithmBenchmark, Long> {

    /**
     * Find benchmark runs for a specific rate limiter algorithm, ordered by test time.
     */
    List<AlgorithmBenchmark> findByAlgorithmOrderByTestedAtDesc(String algorithm);
}
