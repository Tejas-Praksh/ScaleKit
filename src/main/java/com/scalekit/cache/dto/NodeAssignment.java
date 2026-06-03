package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Assignment result for a single key lookup in the consistent hash ring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeAssignment {
    private String key;
    private String assignedNode;
    private int ringPosition;
    private int keyHash;
    private int virtualNodeIndex;
    private long lookupTimeNanos;
}
