package com.scalekit.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async task executor configuration.
 *
 * <p>Provides a dedicated thread pool for background analytics tasks
 * (click counting, geo-tracking) so they never block HTTP handler threads.
 *
 * <p>Uses CallerRunsPolicy so that under extreme load the calling thread
 * runs the task rather than dropping it.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${analytics.executor.core-pool-size:5}")
    private int corePoolSize;

    @Value("${analytics.executor.max-pool-size:20}")
    private int maxPoolSize;

    @Value("${analytics.executor.queue-capacity:1000}")
    private int queueCapacity;

    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "lockWatchdogExecutor")
    public java.util.concurrent.ScheduledExecutorService lockWatchdogExecutor() {
        return java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r);
            thread.setName("lock-watchdog-");
            thread.setDaemon(true);
            return thread;
        });
    }
}
