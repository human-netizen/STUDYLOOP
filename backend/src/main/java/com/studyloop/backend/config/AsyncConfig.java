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

    // Phase 21.1 — the video queue, and it is a queue precisely because the pool is one thread.
    //
    // **The concurrency limit is the executor rather than a check inside the job.** A semaphore or
    // a count-then-decide is a race with whatever request arrives next; a single-slot pool with a
    // bounded backlog is a queue by construction, and the job that waits is QUEUED rather than
    // rejected or lost. `max-concurrent` is configuration because the right value is a property of
    // the machine the renderer runs on, but the honest default is 1: a Manim render saturates the
    // cores it can see, so two at once finish later than the same two run serially and both look
    // broken while they do it.
    //
    // The queue is deliberately shallow. A backlog of 20 renders at three minutes each is an hour,
    // which is longer than anybody waits, and the rejection at the door — a 429 with the daily cap
    // in it — is a better answer than a job that sits QUEUED past the end of the session.
    @Bean("videoExecutor")
    public ThreadPoolTaskExecutor videoExecutor(VideoProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int slots = Math.max(1, properties.maxConcurrent());
        executor.setCorePoolSize(slots);
        executor.setMaxPoolSize(slots);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("video-");
        executor.initialize();
        return executor;
    }
}
