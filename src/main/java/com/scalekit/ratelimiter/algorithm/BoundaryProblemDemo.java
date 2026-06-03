package com.scalekit.ratelimiter.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result model describing and simulating the Fixed Window 2x boundary burst problem.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoundaryProblemDemo {
    private String explanation;
    private int limit;
    private int requestsEndOfWindow;
    private int requestsStartOfWindow;
    private int totalRequestsInShortPeriod;
    private double burstMultiplier;
    private String recommendation;
    private String visualExample;
}
