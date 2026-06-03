package com.scalekit.urlshortener.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * JPA Entity for pre-aggregated daily URL statistics.
 */
@Entity
@Table(name = "url_daily_stats", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"short_code", "date"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlDailyStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 10)
    private String shortCode;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Builder.Default
    @Column(name = "total_clicks", nullable = false)
    private Long totalClicks = 0L;

    @Builder.Default
    @Column(name = "unique_clicks", nullable = false)
    private Long uniqueClicks = 0L;

    @Column(name = "top_country", length = 100)
    private String topCountry;

    @Column(name = "top_device", length = 50)
    private String topDevice;

    @Column(name = "top_referrer", columnDefinition = "TEXT")
    private String topReferrer;
}
