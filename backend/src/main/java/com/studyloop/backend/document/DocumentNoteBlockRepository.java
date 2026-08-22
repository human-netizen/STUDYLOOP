package com.studyloop.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentNoteBlockRepository extends JpaRepository<DocumentNoteBlock, UUID> {

    List<DocumentNoteBlock> findByDocumentIdOrderByOrdinal(UUID documentId);

    // Re-reading a note replaces its blocks rather than appending to them, exactly as re-ingesting
    // a document replaces its chunks.
    void deleteByDocumentId(UUID documentId);
}
