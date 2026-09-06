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

    // Sized from configuration since Phase 23.4, defaults unchanged. Concurrency stays modest to
    // stay inside Supabase's small connection budget — and, on a 512 MB free-tier container, to
    // stay inside the heap: a single page rasterised at 150 DPI is about seven megabytes, so the
    // deployment sets INGESTION_CORE_POOL=1 and ingests one document at a time. The queue absorbs
    // the rest, so an upload still returns 202 and nothing is rejected.
    @Bean("ingestionExecutor")
    public ThreadPoolTaskExecutor ingestionExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int core = Math.max(1, properties.corePoolSize());
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(Math.max(core, properties.maxPoolSize()));
        executor.setQueueCapacity(properties.queueCapacity());
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
