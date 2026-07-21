package com.studyloop.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    long countByDocumentId(UUID documentId);

    // Clears a document's chunks so re-ingestion rebuilds them without unique-constraint clashes.
    void deleteByDocumentId(UUID documentId);
}
