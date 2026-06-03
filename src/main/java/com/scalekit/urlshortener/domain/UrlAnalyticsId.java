package com.scalekit.urlshortener.domain;

import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Composite Primary Key class for UrlAnalytics partitioned table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlAnalyticsId implements Serializable {
    private Long id;
    private Instant clickedAt;
}
