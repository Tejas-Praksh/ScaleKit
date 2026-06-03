package com.scalekit.cache.algorithm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.cache.dto.QueueMessage;
import com.scalekit.cache.dto.QueueStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Simple Redis List-backed Message Queue implementing LPUSH (enqueue),
 * BRPOP/RPOP (dequeue), in-flight message mapping, automatic retries, and DLQ redirects.
 */
@Component
@Slf4j
public class SimpleMessageQueue {

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    private final Map<String, QueueStats> queueStats = new ConcurrentHashMap<>();
    private final Map<String, QueueMessage> inFlightMessages = new ConcurrentHashMap<>();
    private final Map<String, Long> processingStartTimes = new ConcurrentHashMap<>();

    private final int MAX_RETRIES = 3;
    private final String DLQ_SUFFIX = ":dlq";

    @Autowired
    public SimpleMessageQueue(RedisTemplate<String, String> redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueues a payload with empty headers.
     */
    public String enqueue(String queueName, String payload) {
        return enqueue(queueName, payload, Collections.emptyMap());
    }

    /**
     * Enqueues a payload with custom headers.
     */
    public String enqueue(String queueName, String payload, Map<String, String> headers) {
        String messageId = UUID.randomUUID().toString();
        QueueMessage message = QueueMessage.builder()
                .messageId(messageId)
                .payload(payload)
                .enqueuedAt(Instant.now())
                .retryCount(0)
                .headers(headers != null ? headers : Collections.emptyMap())
                .build();

        try {
            String json = objectMapper.writeValueAsString(message);
            redis.opsForList().leftPush(queueName, json);

            // Update stats
            QueueStats stats = getOrCreateStats(queueName);
            stats.setTotalEnqueued(stats.getTotalEnqueued() + 1);
            stats.setCurrentSize(getQueueSize(queueName));

            log.debug("[QUEUE] Enqueued message {} to queue '{}'", messageId, queueName);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to enqueue message to queue '{}': {}", queueName, e.getMessage());
            throw new RuntimeException("Enqueue failure", e);
        }
    }

    /**
     * Dequeues a message, blocking until timeout if the queue is empty.
     */
    public Optional<QueueMessage> dequeue(String queueName, long timeoutSeconds) {
        try {
            String raw = redis.opsForList().rightPop(queueName, timeoutSeconds, TimeUnit.SECONDS);
            return processRawDequeued(queueName, raw);
        } catch (Exception e) {
            log.error("Failed to dequeue (blocking) from queue '{}': {}", queueName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Non-blocking dequeue.
     */
    public Optional<QueueMessage> dequeueNonBlocking(String queueName) {
        try {
            String raw = redis.opsForList().rightPop(queueName);
            return processRawDequeued(queueName, raw);
        } catch (Exception e) {
            log.error("Failed to dequeue (non-blocking) from queue '{}': {}", queueName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<QueueMessage> processRawDequeued(String queueName, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            QueueMessage message = objectMapper.readValue(raw, QueueMessage.class);
            inFlightMessages.put(message.getMessageId(), message);
            processingStartTimes.put(message.getMessageId(), System.currentTimeMillis());
            return Optional.of(message);
        } catch (Exception e) {
            log.error("Failed to parse dequeued message on queue '{}': {}", queueName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Acknowledges successful message processing, updating metrics.
     */
    public void acknowledge(String queueName, String messageId) {
        QueueMessage msg = inFlightMessages.remove(messageId);
        Long startTime = processingStartTimes.remove(messageId);

        if (msg != null && startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            msg.setProcessedAt(Instant.now());

            QueueStats stats = getOrCreateStats(queueName);
            long dequeued = stats.getTotalDequeued() + 1;
            stats.setTotalDequeued(dequeued);
            stats.setCurrentSize(getQueueSize(queueName));

            // Rolling average update
            double oldAvg = stats.getAvgProcessingTimeMs();
            double newAvg = ((oldAvg * (dequeued - 1)) + duration) / dequeued;
            stats.setAvgProcessingTimeMs(newAvg);

            log.debug("[QUEUE] Acknowledged message {} from queue '{}'. Elapsed={}ms", messageId, queueName, duration);
        }
    }

    /**
     * Acknowledges message processing failure, triggering requeue or DLQ redirect.
     */
    public void negativeAcknowledge(String queueName, String messageId, String reason) {
        QueueMessage message = inFlightMessages.remove(messageId);
        processingStartTimes.remove(messageId);

        if (message == null) {
            log.warn("NACK received for unknown/already-processed messageId: {}", messageId);
            return;
        }

        QueueStats stats = getOrCreateStats(queueName);
        stats.setTotalFailed(stats.getTotalFailed() + 1);

        int retries = message.getRetryCount() + 1;
        message.setRetryCount(retries);

        if (retries < MAX_RETRIES) {
            log.info("[QUEUE] Message {} failed on queue '{}' (reason: {}). Retry attempt {}/{}...",
                    messageId, queueName, reason, retries, MAX_RETRIES);
            try {
                String json = objectMapper.writeValueAsString(message);
                redis.opsForList().leftPush(queueName, json);
                stats.setCurrentSize(getQueueSize(queueName));
            } catch (Exception e) {
                log.error("Failed to requeue message {} on queue '{}': {}", messageId, queueName, e.getMessage());
            }
        } else {
            String dlqKey = queueName + DLQ_SUFFIX;
            log.warn("[QUEUE] Message {} exceeded max retries ({}) on queue '{}'. Moving to DLQ: {}",
                    messageId, MAX_RETRIES, queueName, dlqKey);
            try {
                String json = objectMapper.writeValueAsString(message);
                redis.opsForList().leftPush(dlqKey, json);
                stats.setTotalDLQ(stats.getTotalDLQ() + 1);
                stats.setCurrentSize(getQueueSize(queueName));
                stats.setCurrentDLQSize(getDLQSize(queueName));
            } catch (Exception e) {
                log.error("Failed to move message {} to DLQ '{}': {}", messageId, dlqKey, e.getMessage());
            }
        }
    }

    public long getQueueSize(String queueName) {
        try {
            Long size = redis.opsForList().size(queueName);
            return size != null ? size : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public long getDLQSize(String queueName) {
        try {
            Long size = redis.opsForList().size(queueName + DLQ_SUFFIX);
            return size != null ? size : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public List<String> peekMessages(String queueName, int count) {
        try {
            List<String> range = redis.opsForList().range(queueName, 0, count - 1);
            return range != null ? range : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public QueueStats getStats(String queueName) {
        QueueStats stats = getOrCreateStats(queueName);
        stats.setCurrentSize(getQueueSize(queueName));
        stats.setCurrentDLQSize(getDLQSize(queueName));
        return stats;
    }

    private QueueStats getOrCreateStats(String queueName) {
        return queueStats.computeIfAbsent(queueName, name -> QueueStats.builder()
                .queueName(name)
                .totalEnqueued(0)
                .totalDequeued(0)
                .totalFailed(0)
                .totalDLQ(0)
                .currentSize(0)
                .currentDLQSize(0)
                .avgProcessingTimeMs(0.0)
                .build());
    }
}
