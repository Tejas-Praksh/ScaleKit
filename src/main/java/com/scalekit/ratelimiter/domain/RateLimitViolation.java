package com.scalekit.ratelimiter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * JPA Entity representing a rate limit violation audit log entry.
 */
@Entity
@Table(name = "rate_limit_audit_logs", indexes = {
    @Index(name = "idx_audit_identifier", columnList = "identifier"),
    @Index(name = "idx_audit_blocked_at", columnList = "blocked_at")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private RateLimitConfig rule;

    @Column(name = "identifier", nullable = false, length = 100)
    private String identifier;

    @CreatedDate
    @Column(name = "blocked_at", nullable = false, updatable = false)
    private Instant blockedAt;

    @Column(name = "request_uri", nullable = false, length = 255)
    private String requestUri;

    @Column(name = "violation_count", nullable = false)
    private Integer violationCount;
}
