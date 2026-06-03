package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single data point in a click time-series.
 * Used for charting click trends over time at hourly or daily granularity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataPoint {

    /** Human-readable label (e.g. "2026-05-24" or "14:00"). */
    private String label;

    /** Total clicks in this time bucket. */
    private long totalClicks;

    /** Unique clicks (distinct IPs) in this time bucket. */
    private long uniqueClicks;

    /** The exact timestamp this bucket starts at. */
    private Instant timestamp;
}
