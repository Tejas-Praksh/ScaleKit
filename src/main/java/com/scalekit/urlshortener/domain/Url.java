package com.scalekit.urlshortener.domain;

import com.scalekit.common.util.JsonConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;

/**
 * JPA Entity for shortened URLs.
 */
@Entity
@Table(name = "urls", indexes = {
    @Index(name = "idx_urls_short_code", columnList = "short_code", unique = true),
    @Index(name = "idx_urls_custom_alias", columnList = "custom_alias"),
    @Index(name = "idx_urls_created_at", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Url {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 10)
    private String shortCode;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(name = "custom_alias", length = 20)
    private String customAlias;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(name = "is_password_protected", nullable = false)
    private Boolean isPasswordProtected = false;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Builder.Default
    @Column(name = "is_safe", nullable = false)
    private Boolean isSafe = true;

    @Builder.Default
    @Column(name = "click_count", nullable = false)
    private Long clickCount = 0L;

    @Builder.Default
    @Column(name = "unique_click_count", nullable = false)
    private Long uniqueClickCount = 0L;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    @Convert(converter = JsonConverter.class)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;
}
