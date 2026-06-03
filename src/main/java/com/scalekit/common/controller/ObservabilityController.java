package com.scalekit.common.controller;

import com.scalekit.common.dto.AlertDto;
import com.scalekit.common.dto.ApiResponse;
import com.scalekit.common.dto.DashboardSnapshot;
import com.scalekit.common.dto.SystemMetrics;
import com.scalekit.common.gateway.CircuitBreakerRegistry;
import com.scalekit.common.gateway.CircuitBreakerStats;
import com.scalekit.common.gateway.RouteRegistry;
import com.scalekit.common.gateway.RouteRegistry.RouteDefinition;
import com.scalekit.common.observability.DashboardDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final DashboardDataService dashboardDataService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RouteRegistry routeRegistry;

    @GetMapping("/dashboard")
    public ApiResponse<DashboardSnapshot> getDashboard() {
        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        return ApiResponse.success(snapshot, "Dashboard snapshot retrieved successfully");
    }

    @GetMapping("/metrics")
    public ApiResponse<SystemMetrics> getMetrics() {
        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        return ApiResponse.success(snapshot.getSystem(), "System metrics retrieved successfully");
    }

    @GetMapping("/alerts")
    public ApiResponse<List<AlertDto>> getAlerts() {
        DashboardSnapshot snapshot = dashboardDataService.getDashboardSnapshot();
        return ApiResponse.success(snapshot.getActiveAlerts(), "Active alerts retrieved successfully");
    }

    @GetMapping("/circuit-breakers")
    public ApiResponse<Map<String, CircuitBreakerStats>> getCircuitBreakers() {
        Map<String, CircuitBreakerStats> stats = circuitBreakerRegistry.getAllStats();
        return ApiResponse.success(stats, "Circuit breakers retrieved successfully");
    }

    @PostMapping("/circuit-breakers/{route}/reset")
    public ApiResponse<String> resetCircuitBreaker(@PathVariable String route) {
        circuitBreakerRegistry.resetBreaker(route);
        return ApiResponse.success("Circuit breaker reset for route: " + route, "Circuit breaker reset successfully");
    }

    @GetMapping("/routes")
    public List<RouteDefinition> getRoutes() {
        return routeRegistry.getAllRoutes();
    }
}
