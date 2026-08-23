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

    // For text that never had pages to route — an accepted forum answer (9.2).
    @Transactional
    public void replaceChunks(UUID documentId, List<TextChunk> chunks, int pageCount) {
        replaceChunks(documentId, chunks, pageCount, 0);
    }

    @Transactional
    public void replaceChunks(UUID documentId, List<TextChunk> chunks, int pageCount, int visionPages) {
        replaceChunks(documentId, chunks, List.of(), pageCount, visionPages);
    }

    // Text chunks and visual chunks in one transaction, because they are one document's index and
    // a document half-rebuilt is a document that answers questions about two ingests at once.
    @Transactional
    public void replaceChunks(UUID documentId, List<TextChunk> chunks, List<VisualChunk> visuals,
                              int pageCount, int visionPages) {
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
            chunk.setPageNumber(source.pageStart());
            chunk.setPageEnd(source.pageEnd());
            chunk.setSectionPath(source.sectionPath());
            chunk.setContent(source.content());
            chunk.setEmbedText(source.embedText());
            chunk.setTokenCount(source.tokenCount());
            chunkRepository.save(chunk);
        }

        // Appended after the text chunks, sharing the index sequence. No section path, deliberately
        // — a visual chunk is a page rather than a section, and one would make the small-to-big
        // expansion splice a whole section's prose around a figure and then cite the figure for it.
        for (VisualChunk source : visuals) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(document);
            chunk.setModality(ChunkModality.VISUAL);
            chunk.setChunkIndex(source.index());
            chunk.setPageNumber(source.pageNumber());
            chunk.setPageEnd(source.pageNumber());
            chunk.setContent(source.content());
            chunk.setTokenCount(source.tokenCount());
            chunkRepository.save(chunk);
        }
        document.setPageCount(pageCount);
        document.setVisionPages(visionPages);
    }
}
