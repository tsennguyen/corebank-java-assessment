package com.example.corebanking.common.config;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Asynchronous execution configuration backed by Java 21 Virtual Threads so post-commit side
 * effects (such as real-time fraud anomaly evaluation) never block HTTP request threads or pin
 * carrier threads (L3-10, NFR-4, ADR-012, ADR-015).
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = {"taskExecutor", "applicationTaskExecutor", "fraudAsyncExecutor"})
    @Override
    public Executor getAsyncExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("vt-async-");
        executor.setVirtualThreads(true);
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error(
                        "Uncaught exception in @Async method [method={}]: {}",
                        method.toGenericString(),
                        ex.getMessage(),
                        ex);
    }
}
