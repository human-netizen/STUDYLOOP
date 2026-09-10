package com.studyloop.backend.guide;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

// The startup sweep, and the reason a guide is a queue rather than a hope.
//
// The work lives in a thread pool inside this process. A deploy, a crash, or a closed terminal
// takes the pool with it and leaves rows saying PLANNING or WRITING that nothing will ever
// advance. A guide stuck at "3 of 6 sections" forever is worse than one that failed: a failure can
// be retried and a lie cannot — the student waits, refreshes, and eventually decides the product
// is broken, which by then it is. VideoJobReconciler makes the same sweep for the same reason.
//
// **Not re-queued, deliberately.** Resuming means paying for the model calls again on behalf of
// somebody who navigated away twenty minutes ago, and a restart loop would do it every boot. "Ask
// again" is the honest interface, and the person pressing it is the one who still wants the guide.
//
// **One statement rather than a row-by-row walk**, because a partially written guide is already
// durable — the store commits each section as it lands — so there is nothing here to preserve
// except the front of the row. A guide swept at four of six sections keeps those four.
@Slf4j
@Component
@RequiredArgsConstructor
public class StudyGuideReconciler {

    private static final String REASON =
            "This guide was interrupted by a server restart. Nothing is running for it any more "
            + "— ask for it again.";

    private final StudyGuideRepository guides;
    private final Clock clock;

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedGuides() {
        int swept = guides.failUnfinished(REASON, clock.instant());
        if (swept > 0) {
            log.info("Failed {} study guide(s) left unfinished by the previous run", swept);
        }
    }
}
