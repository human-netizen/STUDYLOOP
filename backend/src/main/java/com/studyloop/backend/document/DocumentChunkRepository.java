package com.studyloop.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    // Phase 17 put two kinds of chunk in one table, so every caller that used to mean "this
    // document's chunks" has to say which kind it meant. Embedding is the sharp case: a visual
    // chunk's vector comes from its page image, and running it through the text embedder would
    // overwrite that with an embedding of the caption — silently, and only findable by noticing
    // that figure questions stopped working.
    List<DocumentChunk> findByDocumentIdAndModalityOrderByChunkIndex(UUID documentId,
                                                                    ChunkModality modality);

    long countByDocumentId(UUID documentId);

    long countByDocumentIdAndModality(UUID documentId, ChunkModality modality);

    // Clears a document's chunks so re-ingestion rebuilds them without unique-constraint clashes.
    void deleteByDocumentId(UUID documentId);
}
