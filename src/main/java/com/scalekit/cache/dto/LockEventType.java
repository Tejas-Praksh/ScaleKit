package com.scalekit.cache.dto;

/**
 * Event types for distributed lock audit logs.
 */
public enum LockEventType {
    ACQUIRED,
    RELEASED,
    EXPIRED,
    FAILED,
    EXTENDED,
    WATCHDOG_KICK
}
