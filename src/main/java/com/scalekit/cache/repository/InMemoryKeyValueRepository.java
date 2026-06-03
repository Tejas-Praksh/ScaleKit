package com.scalekit.cache.repository;

import org.springframework.stereotype.Repository;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Simple in‑memory implementation of {@link KeyValueRepository} used for demos and tests.
 * In a production environment this would be replaced by a JPA repository or a MyBatis mapper.
 */
@Repository
public class InMemoryKeyValueRepository implements KeyValueRepository {
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String findByKey(String key) {
        return store.get(key);
    }

    @Override
    public void save(String key, String value) {
        store.put(key, value);
    }

    @Override
    public void deleteByKey(String key) {
        store.remove(key);
    }
}
