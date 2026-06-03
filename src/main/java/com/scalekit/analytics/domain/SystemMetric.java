package com.scalekit.analytics.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * JPA Entity representing aggregated system performance metrics over time.
 */
@Entity
@Table(name = "system_metrics", indexes = {
    @Index(name = "idx_system_metrics_measured_at", columnList = "measured_at")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system", nullable = false, length = 50)
    private String system;

    @Column(name = "total_requests", nullable = false)
    private Long totalRequests;

    @Column(name = "success_requests", nullable = false)
    private Long successRequests;

    @Column(name = "failed_requests", nullable = false)
    private Long failedRequests;

    @Column(name = "success_rate", nullable = false)
    private Double successRate;

    @Column(name = "avg_response_time_ms", nullable = false)
    private Double avgResponseTimeMs;

    @Column(name = "p99_response_time_ms", nullable = false)
    private Double p99ResponseTimeMs;

    @Column(name = "cache_hits", nullable = false)
    private Long cacheHits;

    @Column(name = "cache_misses", nullable = false)
    private Long cacheMisses;

    @Column(name = "cache_hit_rate", nullable = false)
    private Double cacheHitRate;

    @CreatedDate
    @Column(name = "measured_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant measuredAt = Instant.now();
}
