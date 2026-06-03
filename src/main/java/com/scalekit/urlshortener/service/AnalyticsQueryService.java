package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.AnalyticsSummaryDto;
import com.scalekit.urlshortener.dto.RealTimeStatsDto;
import com.scalekit.urlshortener.dto.TimeSeriesDataPoint;
import com.scalekit.urlshortener.dto.UrlResponse;

import java.util.List;

/**
 * Service to query URL click analytics, dashboard data, real-time metrics,
 * time-series charts, and leaderboard stats.
 */
public interface AnalyticsQueryService {

    /**
     * Gets a comprehensive historical and real-time analytics summary for a URL,
     * cached for 5 minutes.
     *
     * @param shortCode shortened URL code
     * @return analytics summary
     */
    AnalyticsSummaryDto getSummary(String shortCode);

    /**
     * Gets the real-time click and visitor statistics directly from Redis.
     *
     * @param shortCode shortened URL code
     * @return real-time statistics
     */
    RealTimeStatsDto getRealTimeStats(String shortCode);

    /**
     * Gets time-series data points for charting click traffic.
     *
     * @param shortCode shortened URL code
     * @param range time range, e.g. "24h" (hourly resolution) or "30d" (daily resolution)
     * @return list of time-series data points
     */
    List<TimeSeriesDataPoint> getClickTimeSeries(String shortCode, String range);

    /**
     * Gets the top N performing URLs sorted by total click counts.
     *
     * @param limit maximum number of URLs to retrieve
     * @return list of URL response objects
     */
    List<UrlResponse> getTopUrls(int limit);
}
