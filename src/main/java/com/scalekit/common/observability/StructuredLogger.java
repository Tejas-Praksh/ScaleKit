package com.scalekit.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalekit.common.util.CorrelationIdUtil;
import com.scalekit.common.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class StructuredLogger {

    private final ObjectMapper mapper;

    public void logRequest(HttpServletRequest req, long startTime) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "REQUEST");
            logMap.put("method", req.getMethod());
            logMap.put("path", req.getRequestURI());
            logMap.put("ip", IpUtil.extractClientIp(req));
            logMap.put("correlationId", CorrelationIdUtil.get());
            logMap.put("userAgent", req.getHeader("User-Agent"));
            logMap.put("timestamp", Instant.now().toString());

            log.info(mapper.writeValueAsString(logMap));
        } catch (Exception e) {
            log.error("Failed to write structured request log", e);
        }
    }

    public void logResponse(int status, long durationMs, String correlationId) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "RESPONSE");
            logMap.put("status", status);
            logMap.put("durationMs", durationMs);
            logMap.put("correlationId", correlationId);
            logMap.put("timestamp", Instant.now().toString());

            log.info(mapper.writeValueAsString(logMap));
        } catch (Exception e) {
            log.error("Failed to write structured response log", e);
        }
    }

    public void logAlgorithmExecution(String algorithm, String operation, long durationMs, boolean success) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "ALGORITHM");
            logMap.put("algorithm", algorithm);
            logMap.put("operation", operation);
            logMap.put("durationMs", durationMs);
            logMap.put("success", success);
            logMap.put("correlationId", CorrelationIdUtil.get());
            logMap.put("timestamp", Instant.now().toString());

            log.info(mapper.writeValueAsString(logMap));
        } catch (Exception e) {
            log.error("Failed to write structured algorithm execution log", e);
        }
    }

    public void logError(String message, Exception ex, Map<String, Object> context) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "ERROR");
            logMap.put("message", message);
            if (ex != null) {
                logMap.put("exceptionClass", ex.getClass().getName());
                logMap.put("exceptionMessage", ex.getMessage());
            }
            if (context != null) {
                logMap.put("context", context);
            }
            logMap.put("correlationId", CorrelationIdUtil.get());
            logMap.put("timestamp", Instant.now().toString());

            log.error(mapper.writeValueAsString(logMap));
        } catch (Exception e) {
            log.error("Failed to write structured error log", e);
        }
    }

    public void logBusinessEvent(String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> logMap = new HashMap<>();
            logMap.put("type", "BUSINESS");
            logMap.put("eventType", eventType);
            if (data != null) {
                logMap.put("data", data);
            }
            logMap.put("correlationId", CorrelationIdUtil.get());
            logMap.put("timestamp", Instant.now().toString());

            log.info(mapper.writeValueAsString(logMap));
        } catch (Exception e) {
            log.error("Failed to write structured business event log", e);
        }
    }
}
