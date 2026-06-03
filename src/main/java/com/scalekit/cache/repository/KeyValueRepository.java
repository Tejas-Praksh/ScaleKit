package com.scalekit.cache.repository;

import org.springframework.stereotype.Repository;

/**
 * Simple key‑value repository abstraction.
 * In a real application this would be a Spring Data JPA repository
 * or a MyBatis mapper. For now it uses an in‑memory map.
 */
@Repository
public interface KeyValueRepository {
    String findByKey(String key);
    void save(String key, String value);
    void deleteByKey(String key);
}
