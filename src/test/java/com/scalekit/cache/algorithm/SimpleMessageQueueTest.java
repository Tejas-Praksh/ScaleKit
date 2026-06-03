package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.QueueMessage;
import com.scalekit.cache.dto.QueueStats;
import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class SimpleMessageQueueTest {

    @Autowired
    private SimpleMessageQueue messageQueue;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private String queueName;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        queueName = "test:queue:" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.delete(queueName);
        redisTemplate.delete(queueName + ":dlq");
    }

    @Test
    void enqueue_addsToQueue() {
        String msgId = messageQueue.enqueue(queueName, "Test Payload");
        assertNotNull(msgId);
        assertEquals(1, messageQueue.getQueueSize(queueName));
    }

    @Test
    void dequeue_returnsMessage() {
        String payload = "Hello World";
        String msgId = messageQueue.enqueue(queueName, payload);

        Optional<QueueMessage> msgOpt = messageQueue.dequeue(queueName, 1);
        assertTrue(msgOpt.isPresent());
        QueueMessage msg = msgOpt.get();
        assertEquals(msgId, msg.getMessageId());
        assertEquals(payload, msg.getPayload());

        // Acknowledge to finish
        messageQueue.acknowledge(queueName, msgId);
    }

    @Test
    void dequeue_emptyQueue_waitsTimeout() {
        long start = System.currentTimeMillis();
        Optional<QueueMessage> msgOpt = messageQueue.dequeue(queueName, 1); // wait 1s
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(msgOpt.isPresent());
        assertTrue(elapsed >= 1000, "Should have blocked for at least 1 second");
    }

    @Test
    void acknowledge_removesFromProcessing() {
        String msgId = messageQueue.enqueue(queueName, "data");
        Optional<QueueMessage> msgOpt = messageQueue.dequeue(queueName, 1);
        assertTrue(msgOpt.isPresent());

        messageQueue.acknowledge(queueName, msgId);
        
        QueueStats stats = messageQueue.getStats(queueName);
        assertEquals(1, stats.getTotalDequeued());
        assertEquals(0, stats.getCurrentSize());
    }

    @Test
    void nack_belowMaxRetries_requeues() {
        String msgId = messageQueue.enqueue(queueName, "retry-data");
        Optional<QueueMessage> msgOpt = messageQueue.dequeue(queueName, 1);
        assertTrue(msgOpt.isPresent());

        // Negative acknowledge -> should requeue
        messageQueue.negativeAcknowledge(queueName, msgId, "First failed execution");
        assertEquals(1, messageQueue.getQueueSize(queueName));

        // Dequeue again
        Optional<QueueMessage> msgOpt2 = messageQueue.dequeue(queueName, 1);
        assertTrue(msgOpt2.isPresent());
        assertEquals(1, msgOpt2.get().getRetryCount());

        messageQueue.acknowledge(queueName, msgId);
    }

    @Test
    void nack_aboveMaxRetries_movesToDLQ() {
        String msgId = messageQueue.enqueue(queueName, "dlq-data");

        // 1st attempt
        Optional<QueueMessage> msg = messageQueue.dequeue(queueName, 1);
        assertTrue(msg.isPresent());
        messageQueue.negativeAcknowledge(queueName, msgId, "Error 1");

        // 2nd attempt
        msg = messageQueue.dequeue(queueName, 1);
        assertTrue(msg.isPresent());
        messageQueue.negativeAcknowledge(queueName, msgId, "Error 2");

        // 3rd attempt
        msg = messageQueue.dequeue(queueName, 1);
        assertTrue(msg.isPresent());
        messageQueue.negativeAcknowledge(queueName, msgId, "Error 3"); // moves to DLQ since retryCount is 3

        assertEquals(0, messageQueue.getQueueSize(queueName));
        assertEquals(1, messageQueue.getDLQSize(queueName));

        QueueStats stats = messageQueue.getStats(queueName);
        assertEquals(1, stats.getTotalDLQ());
    }

    @Test
    void concurrent_producerConsumer() throws InterruptedException {
        int messagesCount = 200;
        int producerThreadsCount = 5;
        int consumerThreadsCount = 5;

        ExecutorService producerExecutor = Executors.newFixedThreadPool(producerThreadsCount);
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(consumerThreadsCount);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(producerThreadsCount + consumerThreadsCount);

        AtomicInteger messagesProcessed = new AtomicInteger(0);

        // 1. Start Consumers
        for (int i = 0; i < consumerThreadsCount; i++) {
            consumerExecutor.submit(() -> {
                try {
                    startLatch.await();
                    while (messagesProcessed.get() < messagesCount) {
                        Optional<QueueMessage> msgOpt = messageQueue.dequeue(queueName, 1);
                        if (msgOpt.isPresent()) {
                            QueueMessage msg = msgOpt.get();
                            messageQueue.acknowledge(queueName, msg.getMessageId());
                            messagesProcessed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 2. Start Producers
        for (int i = 0; i < producerThreadsCount; i++) {
            final int index = i;
            producerExecutor.submit(() -> {
                try {
                    startLatch.await();
                    int batchSize = messagesCount / producerThreadsCount;
                    for (int j = 0; j < batchSize; j++) {
                        messageQueue.enqueue(queueName, "payload-" + index + "-" + j);
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);

        producerExecutor.shutdown();
        consumerExecutor.shutdown();

        assertEquals(messagesCount, messagesProcessed.get(), "All messages should be processed concurrently without loss");
    }
}
