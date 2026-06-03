package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full analytics summary for a single shortened URL.
 *
 * <p>Aggregates data from both the database (historical) and Redis
 * (real-time counters) to provide a comprehensive view of URL performance.
 * Cached in Redis for 5 minutes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummaryDto {

    private String shortCode;
    private String originalUrl;
    private long totalClicks;
    private long uniqueClicks;
    private long clicksLast24Hours;
    private long clicksLast7Days;
    private long clicksLast30Days;
    private double avgClicksPerDay;

    /** Country ISO code → click count. */
    private Map<String, Long> clicksByCountry;

    /** Device type (MOBILE, TABLET, DESKTOP) → click count. */
    private Map<String, Long> clicksByDevice;

    /** Browser name → click count. */
    private Map<String, Long> clicksByBrowser;

    /** OS name → click count. */
    private Map<String, Long> clicksByOs;

    /** Hour label "HH" → click count (last 24 hours). */
    private Map<String, Long> clicksByHour;

    /** Date label "yyyy-MM-dd" → click count (last 30 days). */
    private Map<String, Long> clicksByDay;

    /** Ordered time-series data for charting. */
    private List<TimeSeriesDataPoint> clickTimeSeries;

    private String topCountry;
    private String topDevice;
    private String topReferrer;
    private Instant firstClick;
    private Instant lastClick;
}
