package com.scalekit.urlshortener.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * JPA Entity for logged blocked URL shortening attempts.
 */
@Entity
@Table(name = "blocked_attempts", indexes = {
    @Index(name = "idx_blocked_attempts_blocked_at", columnList = "blocked_at")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockedAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "reputation_score", nullable = false)
    private Integer reputationScore;

    @Column(name = "threats", length = 500)
    private String threats; // Comma-separated list of detected ThreatTypes

    @CreatedDate
    @Column(name = "blocked_at", nullable = false, updatable = false)
    private Instant blockedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;
}
