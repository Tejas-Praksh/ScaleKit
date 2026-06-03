package com.scalekit.urlshortener.service;

/**
 * Service interface for generating unique, atomic sequential IDs.
 *
 * <p>Ensures that the counter is scalable, collision-free, and
 * resilient to caching layer (Redis) failures.
 */
public interface CounterService {

    /**
     * Retrieves the next unique non-negative 64-bit identifier.
     *
     * @return a unique sequential ID
     */
    long getNextId();
}
