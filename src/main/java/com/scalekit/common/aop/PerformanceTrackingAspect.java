package com.scalekit.common.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
public class PerformanceTrackingAspect {

    @Autowired
    private MeterRegistry meterRegistry;

    @Around("execution(* com.scalekit..service.*Service.*(..))")
    public Object trackPerformance(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        Object result = pjp.proceed();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (elapsedMs > 1000) {
            log.warn("SLOW METHOD: {}.{} took {}ms", className, methodName, elapsedMs);
        } else if (elapsedMs > 100) {
            log.debug("{}.{} took {}ms", className, methodName, elapsedMs);
        }
        Timer timer = meterRegistry.timer("method.execution", "class", className, "method", methodName);
        timer.record(elapsedMs, TimeUnit.MILLISECONDS);
        return result;
    }

    @AfterThrowing(pointcut = "execution(* com.scalekit..service.*Service.*(..))", throwing = "ex")
    public void trackException(org.aspectj.lang.JoinPoint jp, Exception ex) {
        String className = jp.getTarget().getClass().getSimpleName();
        String methodName = jp.getSignature().getName();
        log.error("Exception in {}.{}: {}", className, methodName, ex.getClass().getSimpleName());
        meterRegistry.counter("method.execution.errors", "class", className, "method", methodName, "exception", ex.getClass().getSimpleName()).increment();
    }
}
