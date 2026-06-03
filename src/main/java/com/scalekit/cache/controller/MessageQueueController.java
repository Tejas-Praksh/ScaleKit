package com.scalekit.cache.controller;

import com.scalekit.cache.algorithm.SimpleMessageQueue;
import com.scalekit.cache.dto.QueueDemo;
import com.scalekit.cache.dto.QueueMessage;
import com.scalekit.cache.dto.QueueStats;
import com.scalekit.cache.service.MessageQueueService;
import com.scalekit.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller exposing REST endpoints to interact with the Mini Message Queue.
 */
@RestController
@RequestMapping("/api/v1/queue")
@Tag(name = "Message Queue", description = "Endpoints for managing lists, enqueuing/dequeuing payloads, and monitoring DLQ status")
@Slf4j
public class MessageQueueController {

    private final SimpleMessageQueue messageQueue;
    private final MessageQueueService queueService;

    @Autowired
    public MessageQueueController(SimpleMessageQueue messageQueue, MessageQueueService queueService) {
        this.messageQueue = messageQueue;
        this.queueService = queueService;
    }

    @PostMapping("/{name}/enqueue")
    @Operation(summary = "Enqueue a payload into the specified queue")
    public ApiResponse<String> enqueue(@PathVariable String name, @RequestBody EnqueueRequest request) {
        queueService.createQueue(name); // Ensure queue is registered
        String msgId = messageQueue.enqueue(name, request.getPayload(), request.getHeaders());
        return ApiResponse.success(msgId, "Message enqueued successfully");
    }

    @PostMapping("/{name}/dequeue")
    @Operation(summary = "Dequeue a message from the queue, blocking up to timeoutSeconds if dry")
    public ApiResponse<QueueMessage> dequeue(@PathVariable String name, @RequestBody DequeueRequest request) {
        long timeout = request.getTimeoutSeconds() > 0 ? request.getTimeoutSeconds() : 5;
        Optional<QueueMessage> msg = messageQueue.dequeue(name, timeout);
        return msg.map(queueMessage -> ApiResponse.success(queueMessage, "Message dequeued"))
                .orElseGet(() -> ApiResponse.success(null, "Queue is empty (dequeue timeout)"));
    }

    @PostMapping("/{name}/acknowledge")
    @Operation(summary = "Acknowledge successful processing of a message")
    public ApiResponse<Boolean> acknowledge(@PathVariable String name, @RequestBody AcknowledgeRequest request) {
        messageQueue.acknowledge(name, request.getMessageId());
        return ApiResponse.success(true, "Message acknowledged successfully");
    }

    @PostMapping("/{name}/nack")
    @Operation(summary = "Negative acknowledge a message, triggering retry or routing to DLQ")
    public ApiResponse<Boolean> nack(@PathVariable String name, @RequestBody NackRequest request) {
        messageQueue.negativeAcknowledge(name, request.getMessageId(), request.getReason());
        return ApiResponse.success(true, "Message nack processed successfully");
    }

    @GetMapping("/{name}/size")
    @Operation(summary = "Get the current size of the queue")
    public ApiResponse<Long> size(@PathVariable String name) {
        return ApiResponse.success(messageQueue.getQueueSize(name), "Queue size fetched");
    }

    @GetMapping("/{name}/peek")
    @Operation(summary = "Peek at messages in the queue non-destructively")
    public ApiResponse<List<String>> peek(@PathVariable String name, @RequestParam(defaultValue = "10") int count) {
        return ApiResponse.success(messageQueue.peekMessages(name, count), "Messages peeked");
    }

    @GetMapping("/{name}/stats")
    @Operation(summary = "Get execution and telemetry stats for the queue")
    public ApiResponse<QueueStats> stats(@PathVariable String name) {
        return ApiResponse.success(messageQueue.getStats(name), "Queue stats fetched");
    }

    @GetMapping("/{name}/dlq/size")
    @Operation(summary = "Get current size of the Dead Letter Queue (DLQ)")
    public ApiResponse<Long> dlqSize(@PathVariable String name) {
        return ApiResponse.success(messageQueue.getDLQSize(name), "DLQ size fetched");
    }

    @PostMapping("/demo")
    @Operation(summary = "Simulate concurrent publishers and consumers with retry and DLQ logic")
    public ApiResponse<QueueDemo> demo(@RequestBody QueueDemoRequest request) {
        int msgs = request.getMessages() > 0 ? request.getMessages() : 10;
        int cons = request.getConsumers() > 0 ? request.getConsumers() : 3;
        QueueDemo demo = queueService.demonstrateQueue(msgs, cons);
        return ApiResponse.success(demo, "Message Queue demonstration completed");
    }

    // Requests
    @Data
    public static class EnqueueRequest {
        private String payload;
        private Map<String, String> headers;
    }

    @Data
    public static class DequeueRequest {
        private long timeoutSeconds;
    }

    @Data
    public static class AcknowledgeRequest {
        private String messageId;
    }

    @Data
    public static class NackRequest {
        private String messageId;
        private String reason;
    }

    @Data
    public static class QueueDemoRequest {
        private int messages;
        private int consumers;
    }
}
