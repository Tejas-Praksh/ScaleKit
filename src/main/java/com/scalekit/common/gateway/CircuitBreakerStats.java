package com.scalekit.common.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircuitBreakerStats {
    private String routeName;
    private String state;
    private int failures;
    private int successes;
    private int requests;
    private long secondsUntilReset;
}
