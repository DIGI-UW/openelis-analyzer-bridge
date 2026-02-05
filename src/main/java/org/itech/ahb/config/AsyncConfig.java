package org.itech.ahb.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configuration for async task execution.
 * Ensures that async threads are properly managed and shut down gracefully.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("astm-async-");
        // Allow task completion during shutdown
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // But don't wait too long
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}

