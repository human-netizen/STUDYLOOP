package com.studyloop.backend.document;

import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Starts ingestion once — and only once — the upload transaction has committed. AFTER_COMMIT
// guarantees the document row is durably present before the async worker reads it, so the
// pipeline can never outrun its own data. @Async moves the work onto the ingestion executor.
@Component
@RequiredArgsConstructor
public class DocumentIngestionListener {

    private final DocumentIngestionService ingestionService;
    private final DocumentLifecycleService lifecycleService;

    // The ingestion executor is not the request thread, so the usage attribution set at the edge
    // is not in force here. Naming the uploader for the length of the pipeline is what puts the
    // embedding calls — one per batch of chunks, plus the summary — on their account rather than
    // on nobody's.
    @Async("ingestionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        try (var ignored = AiUsageContext.actor(event.uploadedBy())) {
            ingestionService.ingest(event.documentId());
        }
    }

    // Phase 27.2 — the same pipeline over bytes that are already stored. Two lines rather than a
    // parameter on the method above, because the two events say different things and one of them
    // has other listeners: ForumWatchService sweeps a course's open threads when a document is
    // *uploaded*, and re-cutting a document the course has had for a month is not that event.
    //
    // The re-ingest spends the vision quota exactly as an upload does, so it carries an actor for
    // exactly the same reason — without one, a 296-page re-read lands in the ledger under nobody.
    @Async("ingestionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReingestRequested(DocumentReingestRequestedEvent event) {
        try (var ignored = AiUsageContext.actor(event.requestedBy())) {
            ingestionService.ingest(event.documentId());
        }
    }

    // Phase 27.3 — the stored object of a document whose row has just been deleted.
    //
    // AFTER_COMMIT, so a rolled-back delete never destroys bytes; @Async because a bucket delete
    // is an HTTP round trip and the caller has already been told the document is gone. The
    // service swallows a failure here: an orphaned object costs disk, and there is no row left for
    // a retry to find.
    @Async("ingestionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBytesOrphaned(DocumentLifecycleService.DocumentBytesOrphanedEvent event) {
        lifecycleService.removeBytes(event.storagePath());
    }
}
