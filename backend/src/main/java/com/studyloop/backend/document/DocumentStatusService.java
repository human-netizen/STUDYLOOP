package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Owns the persisted status transitions of the ingestion state machine. Each call is its
// own transaction, so a poller watching GET /documents/{id} sees the document advance
// through EXTRACTING → CHUNKING → EMBEDDING in real time. Kept a separate bean from the
// orchestrator so its @Transactional boundaries actually apply (self-invocation wouldn't).
@Service
@RequiredArgsConstructor
public class DocumentStatusService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final DocumentRepository documentRepository;

    // Advances the document to a non-terminal/terminal status, clearing any prior error.
    @Transactional
    public void markStatus(UUID documentId, DocumentStatus status) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.setStatus(status);
        document.setErrorMessage(null);
    }

    // Terminal failure: records the reason (truncated to fit the column) for the client.
    @Transactional
    public void markFailed(UUID documentId, String reason) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage(truncate(reason));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "Ingestion failed.";
        }
        return reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
    }
}
