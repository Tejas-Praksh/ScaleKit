package com.scalekit.common.controller;

import com.scalekit.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Root endpoint — returns a simple welcome/status message
 * so that hitting "/" doesn't produce "No static resource" errors.
 */
@RestController
public class RootController {

    @GetMapping("/")
    public ApiResponse<Map<String, Object>> root() {
        return ApiResponse.success(Map.of(
                "service", "ScaleKit API",
                "status", "running",
                "timestamp", Instant.now().toString(),
                "docs", "/swagger-ui/index.html"
        ));
    }
}
