package com.scalekit.cache.service;

import com.scalekit.cache.algorithm.SimpleMessageQueue;
import com.scalekit.cache.dto.QueueDemo;
import com.scalekit.cache.dto.QueueMessage;
import com.scalekit.cache.dto.QueueStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service coordinating queue lifecycle management and executing multi-threaded publisher-consumer demos.
 */
@Service
@Slf4j
public class MessageQueueService {

    private final SimpleMessageQueue simpleMessageQueue;
    private final RedisTemplate<String, String> redis;
    private final Set<String> registeredQueues = ConcurrentHashMap.newKeySet();

    @Autowired
    public MessageQueueService(SimpleMessageQueue simpleMessageQueue, RedisTemplate<String, String> redis) {
        this.simpleMessageQueue = simpleMessageQueue;
        this.redis = redis;
    }

    public void createQueue(String name) {
        registeredQueues.add(name);
        log.info("Registered queue: {}", name);
    }

    public void destroyQueue(String name) {
        registeredQueues.remove(name);
        redis.delete(name);
        redis.delete(name + ":dlq");
        log.info("Destroyed queue and clean Redis lists for: {}", name);
    }

    public List<String> listQueues() {
        return new ArrayList<>(registeredQueues);
    }

    public QueueStats getQueueStats(String name) {
        return simpleMessageQueue.getStats(name);
    }

    public Map<String, QueueStats> getAllStats() {
        Map<String, QueueStats> allStats = new ConcurrentHashMap<>();
        for (String queue : registeredQueues) {
            allStats.put(queue, getQueueStats(queue));
        }
        return allStats;
    }

    /**
     * Runs a producer-consumer benchmark demonstration.
     * Spawns concurrent producers and consumers, tracking retries and DLQ settlements.
     */
    public QueueDemo demonstrateQueue(int messages, int consumers) {
        String queueName = "demo:queue:" + UUID.randomUUID().toString().substring(0, 8);
        createQueue(queueName);

        long startTime = System.currentTimeMillis();

        // 1. Enqueue all messages (Publisher stage)
        for (int i = 0; i < messages; i++) {
            simpleMessageQueue.enqueue(queueName, "payload-demo-" + i);
        }

        // 2. Consume messages (Concurrent Consumer stage)
        ExecutorService executor = Executors.newFixedThreadPool(consumers);
        AtomicInteger acknowledgedCount = new AtomicInteger(0);
        AtomicInteger dlqCount = new AtomicInteger(0);
        AtomicInteger failedAttempts = new AtomicInteger(0);
        CountDownLatch finishLatch = new CountDownLatch(consumers);

        for (int i = 0; i < consumers; i++) {
            executor.submit(() -> {
                try {
                    while (acknowledgedCount.get() + dlqCount.get() < messages) {
                        // Dequeue with a short timeout to prevent blocking indefinitely if we're waiting for retries
                        Optional<QueueMessage> msgOpt = simpleMessageQueue.dequeue(queueName, 1);
                        if (msgOpt.isPresent()) {
                            QueueMessage msg = msgOpt.get();
                            
                            // Simulate brief processing delay
                            try {
                                Thread.sleep(ThreadLocalRandom.current().nextInt(10, 20));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                            // 80% Success Rate, 20% Stochastic Failures
                            if (ThreadLocalRandom.current().nextDouble() < 0.8) {
                                simpleMessageQueue.acknowledge(queueName, msg.getMessageId());
                                acknowledgedCount.incrementAndGet();
                            } else {
                                failedAttempts.incrementAndGet();
                                simpleMessageQueue.negativeAcknowledge(queueName, msg.getMessageId(), "Stochastic processing error");
                                if (msg.getRetryCount() >= 3) {
                                    dlqCount.incrementAndGet();
                                }
                            }
                        } else {
                            // Yield brief sleep if queue is dry but some messages are still in-flight
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        try {
            finishLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        long duration = System.currentTimeMillis() - startTime;
        QueueDemo demoResult = QueueDemo.builder()
                .messagesSent(messages)
                .messagesReceived(acknowledgedCount.get())
                .messagesFailed(failedAttempts.get())
                .messagesInDLQ(dlqCount.get())
                .processingTimeMs(duration)
                .explanation("Mini Message Queue handles parallel publishing and blocking consumption. Stochastically failed messages are retried up to 3 times before routing to DLQ.")
                .build();

        destroyQueue(queueName);
        return demoResult;
    }
}
