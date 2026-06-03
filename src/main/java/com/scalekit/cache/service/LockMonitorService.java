package com.scalekit.cache.service;

import com.scalekit.cache.dto.LockAuditEvent;
import com.scalekit.cache.dto.LockEventType;
import com.scalekit.cache.dto.LockInfo;
import com.scalekit.cache.dto.LockStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Monitors lock health, aggregates performance telemetry, and maintains a thread-safe audit trail.
 */
@Service
@Slf4j
public class LockMonitorService {

    private final List<LockAuditEvent> auditLog = new CopyOnWriteArrayList<>();
    private final int maxAuditSize = 1000;

    private final AtomicLong totalAcquired = new AtomicLong(0);
    private final AtomicLong totalReleased = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalTimeouts = new AtomicLong(0);

    private final AtomicLong totalHoldTime = new AtomicLong(0);
    private final AtomicLong holdCount = new AtomicLong(0);

    private final AtomicLong totalAcquisitionTime = new AtomicLong(0);
    private final AtomicLong acquisitionCount = new AtomicLong(0);

    private final DistributedLockService lockService;

    @Autowired
    public LockMonitorService(@Lazy DistributedLockService lockService) {
        this.lockService = lockService;
    }

    /**
     * Records a lock audit event, increments corresponding counters, and caps the log size.
     */
    public void recordEvent(LockAuditEvent event) {
        if (event == null) {
            return;
        }

        auditLog.add(event);
        if (auditLog.size() > maxAuditSize) {
            // Remove oldest element (safe as CopyOnWriteArrayList supports concurrent access/modifications)
            auditLog.remove(0);
        }

        // Increment internal statistics based on event type
        if (event.getType() == LockEventType.ACQUIRED) {
            totalAcquired.incrementAndGet();
            totalAcquisitionTime.addAndGet(event.getDurationMs());
            acquisitionCount.incrementAndGet();
        } else if (event.getType() == LockEventType.RELEASED) {
            totalReleased.incrementAndGet();
            totalHoldTime.addAndGet(event.getDurationMs());
            holdCount.incrementAndGet();
        } else if (event.getType() == LockEventType.FAILED) {
            totalFailed.incrementAndGet();
        } else if (event.getType() == LockEventType.EXPIRED) {
            totalTimeouts.incrementAndGet();
        }

        log.info("[LOCK MONITOR] Event: key={}, type={}, owner={}, duration={}ms",
                event.getLockKey(), event.getType(), event.getOwnerId(), event.getDurationMs());
    }

    /**
     * Detects potential deadlocks: locks currently held for longer than twice their initial TTL.
     */
    public List<String> detectDeadlock() {
        List<String> deadlocks = new ArrayList<>();
        Instant now = Instant.now();
        
        if (lockService != null) {
            for (LockInfo info : lockService.getActiveLocks()) {
                long heldDuration = now.toEpochMilli() - info.getAcquiredAt().toEpochMilli();
                if (heldDuration > 2 * info.getTtlMs()) {
                    deadlocks.add(info.getLockKey());
                }
            }
        }
        return deadlocks;
    }

    /**
     * Retrieves the audit log, limited to the specified size.
     */
    public List<LockAuditEvent> getAuditLog(int limit) {
        return auditLog.stream()
                .skip(Math.max(0, auditLog.size() - limit))
                .collect(Collectors.toList());
    }

    /**
     * Computes the aggregated lock telemetry statistics.
     */
    public LockStats getLockStats() {
        List<String> deadlocks = detectDeadlock();
        long activeCount = lockService != null ? lockService.getActiveLocks().size() : 0;

        // Group acquisition counts by resource from the audit log
        Map<String, Long> resourceCounts = new HashMap<>();
        for (LockAuditEvent event : auditLog) {
            if (event.getType() == LockEventType.ACQUIRED) {
                resourceCounts.put(event.getLockKey(), resourceCounts.getOrDefault(event.getLockKey(), 0L) + 1);
            }
        }

        // Sort map in descending order and limit to top 10 resources
        Map<String, Long> top10 = resourceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        double avgHold = holdCount.get() > 0 ? (double) totalHoldTime.get() / holdCount.get() : 0.0;
        double avgAcq = acquisitionCount.get() > 0 ? (double) totalAcquisitionTime.get() / acquisitionCount.get() : 0.0;

        return LockStats.builder()
                .totalAcquired(totalAcquired.get())
                .totalReleased(totalReleased.get())
                .totalFailed(totalFailed.get())
                .totalTimeouts(totalTimeouts.get())
                .currentActiveLocks(activeCount)
                .avgHoldTimeMs(avgHold)
                .avgAcquisitionTimeMs(avgAcq)
                .potentialDeadlocks(deadlocks)
                .locksByResource(top10)
                .build();
    }
}
