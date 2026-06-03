package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Real-time traffic analytics statistics for a shortened URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealTimeStatsDto {
    private String shortCode;
    private long clicksLastMinute;
    private long clicksLastHour;
    private long totalClicksToday;
    private long activeVisitors; // unique IPs in last 5 minutes
}
