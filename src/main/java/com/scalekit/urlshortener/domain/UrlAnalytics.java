package com.scalekit.urlshortener.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * JPA Entity for partitioned URL analytics events.
 */
@Entity
@Table(name = "url_analytics", indexes = {
    @Index(name = "idx_url_analytics_short_code", columnList = "short_code"),
    @Index(name = "idx_url_analytics_clicked_at", columnList = "clicked_at"),
    @Index(name = "idx_url_analytics_country", columnList = "country, clicked_at")
})
@EntityListeners(AuditingEntityListener.class)
@IdClass(UrlAnalyticsId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "url_analytics_seq")
    @SequenceGenerator(name = "url_analytics_seq", sequenceName = "url_analytics_id_seq", allocationSize = 1)
    private Long id;

    @Id
    @CreatedDate
    @Column(name = "clicked_at", nullable = false)
    @Builder.Default
    private Instant clickedAt = Instant.now();

    @Column(name = "short_code", nullable = false, length = 10)
    private String shortCode;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "browser", length = 100)
    private String browser;

    @Column(name = "os", length = 100)
    private String os;

    @Column(name = "referrer", columnDefinition = "TEXT")
    private String referrer;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Builder.Default
    @Column(name = "is_unique", nullable = false)
    private Boolean isUnique = false;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;
}
