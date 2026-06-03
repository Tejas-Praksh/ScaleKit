package com.scalekit.common.gateway;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.*;

@Component
public class RouteRegistry {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteDefinition {
        private String pattern;
        private String name;
        private boolean requiresAuth;
        private boolean rateLimited;
        private int rateLimit;
        private String version;
        private boolean deprecated;
        private String deprecationMessage;
    }

    private final List<RouteDefinition> routes = new ArrayList<>();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public synchronized void register(RouteDefinition route) {
        // Avoid duplicate patterns
        routes.removeIf(r -> r.getPattern().equals(route.getPattern()));
        routes.add(route);
    }

    public Optional<RouteDefinition> getRoute(String path) {
        List<RouteDefinition> matches = new ArrayList<>();
        for (RouteDefinition route : routes) {
            if (pathMatcher.match(route.getPattern(), path)) {
                matches.add(route);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        // Most specific match wins
        matches.sort((r1, r2) -> pathMatcher.getPatternComparator(path).compare(r1.getPattern(), r2.getPattern()));
        return Optional.of(matches.get(0));
    }

    public List<RouteDefinition> getAllRoutes() {
        return Collections.unmodifiableList(routes);
    }

    @PostConstruct
    public void registerDefaultRoutes() {
        register(RouteDefinition.builder()
                .pattern("/api/v1/urls/**")
                .name("url-shortener")
                .rateLimited(true)
                .rateLimit(100)
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/rate-limiter/**")
                .name("rate-limiter")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/cache/**")
                .name("cache")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/consistent-hash/**")
                .name("consistent-hash")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/bloom-filter/**")
                .name("bloom-filter")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/locks/**")
                .name("locking")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/leader/**")
                .name("leader")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/queue/**")
                .name("queue")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/analytics/**")
                .name("analytics")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/api/v1/benchmark/**")
                .name("benchmark")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/actuator/**")
                .name("actuator")
                .version("v1")
                .build());

        register(RouteDefinition.builder()
                .pattern("/swagger-ui/**")
                .name("swagger-ui")
                .version("v1")
                .build());

        // Register admin routes as requiring authorization
        register(RouteDefinition.builder()
                .pattern("/api/v1/admin/**")
                .name("admin")
                .requiresAuth(true)
                .version("v1")
                .build());
    }
}
