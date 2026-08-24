package com.studyloop.backend.video;

import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Starts the render once the job row has committed, and not a moment before.
//
// AFTER_COMMIT rather than a direct @Async call from the service, and the difference is not
// theoretical: the executor picks the task up immediately, and a runner that reads the job by id
// inside the same millisecond the inserting transaction is still open finds nothing and fails a
// job that was never actually broken. DocumentIngestionListener learned this in Phase 4.2; this is
// the same listener with a different executor.
//
// The single-slot executor means a second job simply waits here, in the pool's queue, while its
// row says QUEUED. That is the whole queue: no broker, no polling loop, and no state that lives
// somewhere the database cannot see it — the startup sweep is what covers the one case this
// arrangement cannot, which is the process dying with work in that pool.
@Component
@RequiredArgsConstructor
public class VideoJobListener {

    private final VideoJobRunner runner;

    @Async("videoExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoJobQueued(VideoJobQueuedEvent event) {
        try (var ignored = AiUsageContext.actor(event.requestedBy())) {
            runner.run(event.jobId(), event.requestedBy());
        }
    }
}
