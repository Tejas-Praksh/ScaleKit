package com.scalekit.cache.algorithm;

import com.scalekit.cache.dto.QueueMessage;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class MessageQueueOrderTest {

    @Autowired
    private SimpleMessageQueue messageQueue;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private String queueName;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");

        queueName = "test:queue:order:" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.delete(queueName);
    }

    @Test
    void fifo_order_maintained() {
        // Enqueue A, B, C
        messageQueue.enqueue(queueName, "A");
        messageQueue.enqueue(queueName, "B");
        messageQueue.enqueue(queueName, "C");

        // Dequeue should yield A, then B, then C
        Optional<QueueMessage> msgA = messageQueue.dequeue(queueName, 1);
        assertTrue(msgA.isPresent());
        assertEquals("A", msgA.get().getPayload());
        messageQueue.acknowledge(queueName, msgA.get().getMessageId());

        Optional<QueueMessage> msgB = messageQueue.dequeue(queueName, 1);
        assertTrue(msgB.isPresent());
        assertEquals("B", msgB.get().getPayload());
        messageQueue.acknowledge(queueName, msgB.get().getMessageId());

        Optional<QueueMessage> msgC = messageQueue.dequeue(queueName, 1);
        assertTrue(msgC.isPresent());
        assertEquals("C", msgC.get().getPayload());
        messageQueue.acknowledge(queueName, msgC.get().getMessageId());

        // Queue should be empty now
        assertEquals(0, messageQueue.getQueueSize(queueName));
    }
}
