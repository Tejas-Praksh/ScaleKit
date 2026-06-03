package com.scalekit.analytics.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * JPA Entity representing comparative benchmark results for different algorithms.
 */
@Entity
@Table(name = "algorithm_benchmarks", indexes = {
    @Index(name = "idx_benchmarks_tested_at", columnList = "tested_at")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmBenchmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "algorithm", nullable = false, length = 50)
    private String algorithm;

    @Column(name = "requests_per_second", nullable = false)
    private Double requestsPerSecond;

    @Column(name = "latency_ms", nullable = false)
    private Double latencyMs;

    @Column(name = "throughput", nullable = false)
    private Long throughput;

    @Column(name = "error_rate", nullable = false)
    private Double errorRate;

    @CreatedDate
    @Column(name = "tested_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant testedAt = Instant.now();
}
