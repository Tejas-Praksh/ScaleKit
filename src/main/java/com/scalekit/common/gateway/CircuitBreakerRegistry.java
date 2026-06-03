package com.scalekit.common.gateway;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CircuitBreakerRegistry {

    public static class RouteCircuitBreaker {
        private final String routeName;
        private CircuitState state = CircuitState.CLOSED;
        private final AtomicInteger failures = new AtomicInteger(0);
        private final AtomicInteger successes = new AtomicInteger(0);
        private final AtomicInteger requests = new AtomicInteger(0);
        private Instant openedAt;
        private final int failureThreshold = 5;
        private final long resetTimeMs = 10000;
        private final int halfOpenRequests = 3;

        public RouteCircuitBreaker(String routeName) {
            this.routeName = routeName;
        }

        public synchronized boolean allowRequest() {
            if (state == CircuitState.CLOSED) {
                return true;
            }
            if (state == CircuitState.OPEN) {
                if (Instant.now().toEpochMilli() - openedAt.toEpochMilli() >= resetTimeMs) {
                    // Transition to HALF_OPEN
                    state = CircuitState.HALF_OPEN;
                    failures.set(0);
                    successes.set(0);
                    requests.set(1);
                    return true;
                }
                return false;
            }
            if (state == CircuitState.HALF_OPEN) {
                int reqCount = requests.incrementAndGet();
                if (reqCount <= halfOpenRequests) {
                    return true;
                } else {
                    requests.decrementAndGet();
                    return false;
                }
            }
            return true;
        }

        public synchronized void recordSuccess() {
            if (state == CircuitState.HALF_OPEN) {
                int succ = successes.incrementAndGet();
                if (succ >= halfOpenRequests) {
                    state = CircuitState.CLOSED;
                    failures.set(0);
                    successes.set(0);
                    requests.set(0);
                    openedAt = null;
                }
            }
        }

        public synchronized void recordFailure() {
            if (state == CircuitState.CLOSED) {
                int fails = failures.incrementAndGet();
                if (fails >= failureThreshold) {
                    state = CircuitState.OPEN;
                    openedAt = Instant.now();
                }
            } else if (state == CircuitState.HALF_OPEN) {
                // Any failure in HALF_OPEN immediately trips back to OPEN
                state = CircuitState.OPEN;
                openedAt = Instant.now();
                failures.set(1);
                successes.set(0);
                requests.set(0);
            }
        }

        public synchronized CircuitBreakerStats getStats() {
            long secondsUntilReset = 0;
            if (state == CircuitState.OPEN && openedAt != null) {
                long remainingMs = openedAt.toEpochMilli() + resetTimeMs - Instant.now().toEpochMilli();
                secondsUntilReset = Math.max(0, remainingMs / 1000);
            }
            return CircuitBreakerStats.builder()
                    .routeName(routeName)
                    .state(state.name())
                    .failures(failures.get())
                    .successes(successes.get())
                    .requests(requests.get())
                    .secondsUntilReset(secondsUntilReset)
                    .build();
        }
    }

    private final Map<String, RouteCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public boolean allowRequest(String route) {
        return breakers.computeIfAbsent(route, RouteCircuitBreaker::new).allowRequest();
    }

    public void recordSuccess(String route) {
        RouteCircuitBreaker breaker = breakers.get(route);
        if (breaker != null) {
            breaker.recordSuccess();
        }
    }

    public void recordFailure(String route) {
        breakers.computeIfAbsent(route, RouteCircuitBreaker::new).recordFailure();
    }

    public CircuitBreakerStats getStats(String route) {
        RouteCircuitBreaker breaker = breakers.get(route);
        if (breaker == null) {
            return CircuitBreakerStats.builder()
                    .routeName(route)
                    .state("CLOSED")
                    .build();
        }
        return breaker.getStats();
    }

    public Map<String, CircuitBreakerStats> getAllStats() {
        Map<String, CircuitBreakerStats> statsMap = new HashMap<>();
        for (Map.Entry<String, RouteCircuitBreaker> entry : breakers.entrySet()) {
            statsMap.put(entry.getKey(), entry.getValue().getStats());
        }
        return statsMap;
    }

    public void forceOpenTimeForTesting(String route, Instant time) {
        RouteCircuitBreaker breaker = breakers.computeIfAbsent(route, RouteCircuitBreaker::new);
        synchronized (breaker) {
            breaker.state = CircuitState.OPEN;
            breaker.openedAt = time;
            breaker.failures.set(5);
        }
    }

    public void resetBreaker(String route) {
        RouteCircuitBreaker breaker = breakers.get(route);
        if (breaker != null) {
            synchronized (breaker) {
                breaker.state = CircuitState.CLOSED;
                breaker.failures.set(0);
                breaker.successes.set(0);
                breaker.requests.set(0);
                breaker.openedAt = null;
            }
        }
    }
}
