package com.studyloop.backend.guide;

import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Starts the generation once the guide row has committed, and not a moment before.
//
// AFTER_COMMIT rather than a direct @Async call from the service, for the reason
// DocumentIngestionListener learned in Phase 4.2 and VideoJobListener repeated in 21.1: the
// executor picks the task up immediately, and a runner that reads the row by id while the
// inserting transaction is still open finds nothing and fails a job that was never broken.
//
// **The usage scope is opened here rather than inside the runner**, so every model call the
// generation makes — the outline and each section — is attributed to the member who asked, on a
// thread no request ever touched. Without it the spending would land under no actor at all, and
// the per-user budget that is supposed to price this feature would never see it.
@Component
@RequiredArgsConstructor
public class StudyGuideListener {

    private final StudyGuideRunner runner;

    @Async("guideExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStudyGuideQueued(StudyGuideQueuedEvent event) {
        try (var ignored = AiUsageContext.actor(event.requestedBy())) {
            runner.run(event.guideId(), event.requestedBy());
        }
    }
}
