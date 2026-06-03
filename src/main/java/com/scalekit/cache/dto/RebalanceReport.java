package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Summary report of a rebalancing analysis for the consistent hash ring.
 * Includes statistical metrics and lists of overloaded/underloaded nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RebalanceReport {
    /** Map of node name to number of keys (or lookups) observed */
    private Map<String, Long> keysPerNode;
    /** Mean number of keys per node */
    private double mean;
    /** Standard deviation of keys per node */
    private double standardDeviation;
    /** Coefficient of variation (stdDev / mean) */
    private double coefficientOfVariation;
    /** Whether rebalancing is needed */
    private boolean needsRebalancing;
    /** Recommendation message */
    private String recommendation;
    /** Nodes that are overloaded */
    private List<String> overloadedNodes;
    /** Nodes that are underloaded */
    private List<String> underloadedNodes;
}
