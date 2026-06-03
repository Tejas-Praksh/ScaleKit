package com.scalekit.tests;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
    Base62EncoderCorrectnessTest.class,
    LRUCacheCorrectnessTest.class,
    LFUCacheCorrectnessTest.class,
    BloomFilterCorrectnessTest.class,
    ConsistentHashCorrectnessTest.class,
    TokenBucketCorrectnessTest.class,
    SlidingWindowCorrectnessTest.class,
    FixedWindowCorrectnessTest.class
})
public class AlgorithmCorrectnessTestSuite {
}
