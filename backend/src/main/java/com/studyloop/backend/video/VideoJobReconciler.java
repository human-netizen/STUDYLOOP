package com.studyloop.backend.video;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

// The startup sweep, and the reason this is a queue rather than a hope.
//
// The work lives in a thread pool inside this process. A deploy, a crash, or somebody closing the
// terminal takes the pool with it — and leaves rows saying PLANNING or RENDERING that nothing is
// ever going to advance. A job stuck at "rendering scene 3 of 6" forever is worse than a job that
// failed, because a failure can be retried and a lie cannot: the student waits, refreshes, and
// eventually decides the product is broken, which by then is true.
//
// So every unfinished job is failed at boot, with the honest reason. QUEUED is swept too: those
// jobs were accepted by a runtime that no longer exists and no live executor holds them.
//
// **Not re-queued, deliberately.** Resuming would mean re-running planning and paying for the
// model calls again, on behalf of somebody who has long since navigated away — and a restart loop
// would do it on every boot. One button that says "try again" is the honest interface for this,
// and the person pressing it is the one who still wants the video.
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoJobReconciler {

    private static final String REASON =
            "This render was interrupted by a server restart. Nothing is running for it any more "
            + "— ask for the video again.";

    private final VideoJobRepository jobRepository;
    private final VideoJobStatusService statusService;

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        List<VideoJob> unfinished = jobRepository.findUnfinished();
        if (unfinished.isEmpty()) {
            return;
        }
        log.info("Failing {} video job(s) left unfinished by the previous run", unfinished.size());
        for (VideoJob job : unfinished) {
            statusService.markFailed(job.getId(), REASON);
        }
    }
}
