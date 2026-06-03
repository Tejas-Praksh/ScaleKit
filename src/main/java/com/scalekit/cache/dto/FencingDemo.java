package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Results of a fencing token demonstration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FencingDemo {
    private String resource;
    private long firstToken;
    private boolean firstWriteSuccess;
    private long secondToken;
    private boolean secondWriteSuccess;
    private long staleToken;
    private boolean staleWriteSuccess;
    private String explanation;
}
