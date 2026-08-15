package com.studyloop.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentTermRepository extends JpaRepository<DocumentTerm, UUID> {

    List<DocumentTerm> findByDocumentIdOrderByTermIndex(UUID documentId);

    // Clears a document's glossary so regeneration replaces it instead of clashing with the
    // (document_id, term_index) unique index.
    void deleteByDocumentId(UUID documentId);
}
