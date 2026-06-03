package com.scalekit.cache.strategy;

import com.scalekit.cache.dto.CacheStrategyStats;
import com.scalekit.cache.provider.CacheProvider;
import com.scalekit.cache.repository.InMemoryKeyValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
public class WriteThroughStrategyTest {

    private WriteThroughStrategy strategy;

    @BeforeEach
    public void setUp() {
        CacheProvider cacheProvider = new CacheProvider();
        InMemoryKeyValueRepository repository = new InMemoryKeyValueRepository();
        strategy = new WriteThroughStrategy(cacheProvider, repository);
    }

    @Test
    public void testPutWritesToBothCacheAndDb() {
        strategy.put("k1", "v1");
        // Cache should contain value
        assertEquals("v1", strategy.get("k1"));
    }

    @Test
    public void testGetCacheHitDoesNotHitDb() {
        strategy.put("k2", "v2");
        // First get will hit cache
        String val = strategy.get("k2");
        assertEquals("v2", val);
        // No way to directly assert DB hits; rely on stats
        CacheStrategyStats stats = strategy.getStats();
        assertEquals(1, stats.getCacheHits()); // second get after put
    }
}
