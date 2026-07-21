package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Persists a document's chunks and records its page count. Separate transactional bean so
// the ingestion orchestrator can commit chunk-writing as one atomic step.
@Service
@RequiredArgsConstructor
public class DocumentChunkService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    @Transactional
    public void replaceChunks(UUID documentId, List<TextChunk> chunks, int pageCount) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        // Clear any prior chunks first so re-ingestion rebuilds cleanly; flush so the deletes
        // land before the inserts and can't collide on the (document, chunk_index) unique index.
        chunkRepository.deleteByDocumentId(documentId);
        chunkRepository.flush();

        for (TextChunk source : chunks) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(document);
            chunk.setChunkIndex(source.index());
            chunk.setPageNumber(source.pageNumber());
            chunk.setContent(source.content());
            chunk.setTokenCount(source.tokenCount());
            chunkRepository.save(chunk);
        }
        document.setPageCount(pageCount);
    }
}
