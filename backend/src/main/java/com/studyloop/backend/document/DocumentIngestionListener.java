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
}
