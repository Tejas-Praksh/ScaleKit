package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Result of an LRU cache simulation.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LRUSimulationResult {
    private int capacity;
    private List<String> accessSequence;
    private List<String> cacheStateAfterEach; // state of cache (MRU->LRU) after each access
    private List<Boolean> hitOrMiss; // true if hit, false if miss
    private int totalHits;
    private int totalMisses;
    private int totalEvictions;
    private double hitRate;
    private String explanation; // human‑readable description of the simulation
}
