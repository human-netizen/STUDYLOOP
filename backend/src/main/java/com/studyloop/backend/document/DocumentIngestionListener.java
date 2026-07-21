package com.studyloop.backend.document;

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

    @Async("ingestionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        ingestionService.ingest(event.documentId());
    }
}
