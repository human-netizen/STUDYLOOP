package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final PdfTextExtractor textExtractor;
    private final TextChunker textChunker;
    private final DocumentChunkService chunkService;

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
            byte[] bytes = storageService.read(storagePath);
            List<PageText> pages = textExtractor.extract(bytes);

            statusService.markStatus(documentId, DocumentStatus.CHUNKING);
            List<TextChunk> chunks = textChunker.chunk(pages);
            chunkService.replaceChunks(documentId, chunks, pages.size());

            statusService.markStatus(documentId, DocumentStatus.EMBEDDING);
            // 4.4: embed each chunk into a pgvector column.

            statusService.markStatus(documentId, DocumentStatus.READY);
        } catch (Exception e) {
            statusService.markFailed(documentId, e.getMessage());
        }
    }
}
