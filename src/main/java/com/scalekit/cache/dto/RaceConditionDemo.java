package com.scalekit.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Results of a multi-threaded race condition demonstration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaceConditionDemo {
    private int threads;
    private int iterationsPerThread;
    private int expectedFinal;
    private int actualWithoutLock;
    private int actualWithLock;
    private int lostUpdatesWithoutLock;
    private boolean lockingFixed;
    private String explanation;
}
