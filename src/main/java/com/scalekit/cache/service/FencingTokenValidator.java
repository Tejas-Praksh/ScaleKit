package com.scalekit.cache.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates fencing tokens to prevent stale lock updates during long GC pauses.
 */
@Service
@Slf4j
public class FencingTokenValidator {

    private final Map<String, Long> lastTokenPerResource = new ConcurrentHashMap<>();

    /**
     * Validates if the fencing token is valid for a resource (i.e. strictly greater than the last seen token).
     *
     * @param resource the resource identifier (e.g. database row, file path)
     * @param fencingToken the monotonically increasing token associated with the lock
     * @return true if token is fresh, false if it is stale
     */
    public boolean validate(String resource, long fencingToken) {
        Long lastToken = lastTokenPerResource.get(resource);
        if (lastToken == null || fencingToken > lastToken) {
            lastTokenPerResource.put(resource, fencingToken);
            return true;
        } else {
            log.warn("Stale token! resource={}, token={}, lastToken={}", resource, fencingToken, lastToken);
            return false;
        }
    }

    /**
     * Resets/removes the tracking for a resource.
     */
    public void reset(String resource) {
        lastTokenPerResource.remove(resource);
    }

    public Map<String, Long> getLastTokenPerResource() {
        return lastTokenPerResource;
    }
}
