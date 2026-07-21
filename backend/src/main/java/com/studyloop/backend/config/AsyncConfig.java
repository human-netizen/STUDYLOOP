package com.studyloop.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Enables @Async and provides the executor that runs document ingestion off the request
// thread, so an upload returns 202 immediately while extraction/chunking/embedding proceed
// in the background.
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("ingestionExecutor")
    public ThreadPoolTaskExecutor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Queue backlog before spawning beyond the core pool; ingestion is I/O-bound and
        // we keep concurrency modest to stay within Supabase's small connection budget.
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-");
        executor.initialize();
        return executor;
    }
}
