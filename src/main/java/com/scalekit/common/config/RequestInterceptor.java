package com.scalekit.common.config;

import com.scalekit.common.constants.SystemConstants;
import com.scalekit.common.util.CorrelationIdUtil;
import com.scalekit.common.util.PerformanceUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP request interceptor for cross-cutting concerns.
 *
 * <p>Handles correlation ID propagation, execution time tracking,
 * and request/response logging for every incoming request.
 */
@Component
public class RequestInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestInterceptor.class);
    private static final String START_TIME_ATTR = "scalekit.requestStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Generate or extract correlation ID
        String correlationId = request.getHeader(SystemConstants.CORRELATION_ID_HEADER);
        if (StringUtils.isBlank(correlationId)) {
            correlationId = CorrelationIdUtil.generateId();
        }
        CorrelationIdUtil.set(correlationId);

        // Record start time
        long startTime = PerformanceUtil.startTimer();
        request.setAttribute(START_TIME_ATTR, startTime);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // Calculate execution time
            Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
            long executionTimeMs = 0;
            if (startTime != null) {
                executionTimeMs = PerformanceUtil.elapsedMs(startTime);
            }

            // Set response headers
            String correlationId = CorrelationIdUtil.get();
            if (correlationId != null) {
                response.setHeader(SystemConstants.CORRELATION_ID_HEADER, correlationId);
            }
            response.setHeader(SystemConstants.EXECUTION_TIME_HEADER, executionTimeMs + "ms");

            // Log request summary
            log.info("[{}] {} {} -> {} ({} ms)",
                    correlationId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    executionTimeMs);
        } finally {
            CorrelationIdUtil.clear();
        }
    }
}
