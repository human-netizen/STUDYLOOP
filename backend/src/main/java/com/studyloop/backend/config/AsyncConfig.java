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

    // Runs SSE chat streams off the request thread. Each stream holds a thread for the whole
    // model response (seconds), so this pool is separate from ingestion and sized for a handful
    // of concurrent chatters; excess requests wait briefly in the queue.
    @Bean("chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("chat-");
        executor.initialize();
        return executor;
    }
}
