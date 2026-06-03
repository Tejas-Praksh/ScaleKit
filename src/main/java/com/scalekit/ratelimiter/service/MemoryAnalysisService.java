package com.scalekit.ratelimiter.service;

import com.scalekit.ratelimiter.dto.MemoryComparisonDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service providing theoretical and mathematical memory analysis comparing rate limiting algorithms in Redis.
 */
@Service
public class MemoryAnalysisService {

    /**
     * Analyzes and estimates RAM consumption across algorithms.
     */
    public List<MemoryComparisonDto> analyzeMemoryUsage() {
        List<MemoryComparisonDto> comparison = new ArrayList<>();

        // 1. Token Bucket
        comparison.add(MemoryComparisonDto.builder()
                .algorithm("TOKEN_BUCKET")
                .memoryPerUserBytes(100) // ~100 bytes (Hash fields: tokens + last_refill)
                .memoryFor1000UsersKb(100) // 1000 * 100 / 1000 = 100 KB
                .memoryFor1MUsersGb(100) // 1M * 100 bytes / 1024 / 1024 = ~95MB (rounded to 100MB for display)
                .explanation("Extremely memory efficient. Stores exactly two numbers (token count and timestamp) inside a single Redis Hash per user. Memory is constant (O(1)) regardless of request volume.")
                .build());

        // 2. Sliding Window
        comparison.add(MemoryComparisonDto.builder()
                .algorithm("SLIDING_WINDOW")
                .memoryPerUserBytes(5000) // ~5KB per user (assuming 100 requests in window * 50 bytes per element in Sorted Set)
                .memoryFor1000UsersKb(5000) // 5 MB
                .memoryFor1MUsersGb(5000) // ~5 GB
                .explanation("High memory footprint. Uses a Redis Sorted Set (ZSET) per user, storing a timestamp and unique request ID for every single request within the active window (O(N) memory per user).")
                .build());

        // 3. Fixed Window
        comparison.add(MemoryComparisonDto.builder()
                .algorithm("FIXED_WINDOW")
                .memoryPerUserBytes(50) // ~50 bytes per user (single integer key)
                .memoryFor1000UsersKb(50) // 50 KB
                .memoryFor1MUsersGb(50) // ~50 MB
                .explanation("Lowest memory footprint. Stores a single integer counter per user that resets at set intervals. Constant O(1) memory, but vulnerable to boundary burst spikes.")
                .build());

        return comparison;
    }
}
