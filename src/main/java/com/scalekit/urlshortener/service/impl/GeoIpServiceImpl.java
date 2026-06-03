package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.dto.GeoLocationDetails;
import com.scalekit.urlshortener.service.GeoIpService;
import org.springframework.stereotype.Service;

/**
 * Mock-based implementation of GeoIpService.
 * Simulates MaxMind DB lookup based on common IP addresses and ranges.
 */
@Service
public class GeoIpServiceImpl implements GeoIpService {

    @Override
    public GeoLocationDetails lookup(String ipAddress) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            return GeoLocationDetails.builder()
                    .country("Unknown")
                    .city("Unknown")
                    .build();
        }

        String ip = ipAddress.trim();

        // 1. Check local/loopback
        if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip)) {
            return GeoLocationDetails.builder()
                    .country("Local")
                    .city("Local")
                    .build();
        }

        // 2. Predefined mock lookups for tests
        if ("8.8.8.8".equals(ip)) {
            return GeoLocationDetails.builder()
                    .country("United States")
                    .city("Mountain View")
                    .build();
        }

        if ("1.1.1.1".equals(ip)) {
            return GeoLocationDetails.builder()
                    .country("Australia")
                    .city("Sydney")
                    .build();
        }

        // 3. Simple heuristics for simulation
        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")) {
            return GeoLocationDetails.builder()
                    .country("Local")
                    .city("Local")
                    .build();
        }

        // Fallback
        return GeoLocationDetails.builder()
                .country("Unknown")
                .city("Unknown")
                .build();
    }
}
