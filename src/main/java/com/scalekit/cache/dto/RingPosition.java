package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a snapshot of a virtual node's position on the consistent hash ring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RingPosition {
    /** Ring position (hash value) */
    private int position;
    /** Name of the physical node */
    private String nodeName;
    /** Index of the virtual node */
    private int virtualNodeIndex;
    /** Identifier of the virtual node */
    private String virtualNodeId;
    /** Flag indicating this is a virtual node (always true) */
    private boolean isVirtualNode;
}
