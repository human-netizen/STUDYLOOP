package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Orchestrates the ingestion pipeline for one document, driving its status forward and
// recording a FAILED status if any step throws. Deliberately not @Async or @Transactional
// itself: the async trigger lives in DocumentIngestionListener, and each status write is
// its own transaction (via DocumentStatusService) so progress is observable step by step.
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final DocumentStatusService statusService;

    public void ingest(UUID documentId) {
        String storagePath = documentRepository.findById(documentId)
                .map(Document::getStoragePath)
                .orElse(null);
        if (storagePath == null) {
            // The document was removed (e.g. its course was deleted) before ingestion ran.
            return;
        }

        try {
            statusService.markStatus(documentId, DocumentStatus.EXTRACTING);
            // 4.2 confirms the stored bytes round-trip; 4.3 will parse them into page text.
            storageService.read(storagePath);

            statusService.markStatus(documentId, DocumentStatus.CHUNKING);
            // 4.3: split the extracted text into overlapping chunks and persist them.

            statusService.markStatus(documentId, DocumentStatus.EMBEDDING);
            // 4.4: embed each chunk into a pgvector column.

            statusService.markStatus(documentId, DocumentStatus.READY);
        } catch (Exception e) {
            statusService.markFailed(documentId, e.getMessage());
        }
    }
}
