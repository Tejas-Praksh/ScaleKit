package com.scalekit.common.gateway;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ApiVersionManager {

    private final Set<String> supportedVersions = Set.of("v1");
    private final Set<String> deprecatedVersions = Set.of();

    public boolean isVersionSupported(String version) {
        return supportedVersions.contains(version);
    }

    public boolean isVersionDeprecated(String version) {
        return deprecatedVersions.contains(version);
    }

    public String extractVersion(String path) {
        if (path == null || path.isBlank()) {
            return "v1";
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.matches("^v[0-9]+$")) {
                return segment;
            }
        }
        return "v1";
    }

    public void addDeprecationHeaders(HttpServletResponse response, String version) {
        response.addHeader("Deprecation", "true");
        response.addHeader("Sunset", "2026-12-31T23:59:59Z");
        response.addHeader("Link", "<http://localhost:8080/api/v2>; rel=\"successor-version\"");
    }
}
