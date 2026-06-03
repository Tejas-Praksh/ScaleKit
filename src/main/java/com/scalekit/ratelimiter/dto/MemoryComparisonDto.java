package com.scalekit.ratelimiter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Memory usage profile comparing different rate limiting algorithms.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryComparisonDto {
    private String algorithm;
    private long memoryPerUserBytes;
    private long memoryFor1000UsersKb;
    private long memoryFor1MUsersGb;
    private String explanation;
}
