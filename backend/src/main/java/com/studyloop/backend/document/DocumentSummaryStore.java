package com.studyloop.backend.document;

import com.studyloop.backend.document.dto.DocumentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The database half of Phase 8.2, kept a separate bean from DocumentSummaryService for the same
// reason DocumentStatusService is separate from the ingestion orchestrator: @Transactional is
// applied by a proxy, so a method the orchestrator calls on itself would silently run without a
// transaction. Splitting it also keeps the slow model call outside any open transaction.
@Service
@RequiredArgsConstructor
class DocumentSummaryStore {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentTermRepository termRepository;

    @Transactional(readOnly = true)
    public DocumentSummaryResponse read(UUID courseId, UUID documentId) {
        Document document = require(courseId, documentId);
        return DocumentSummaryResponse.from(
                document, termRepository.findByDocumentIdOrderByTermIndex(documentId));
    }

    // Whether a cached summary already exists, plus the source text a generation would use.
    // Answered in one short transaction so the caller can decide without holding a connection.
    @Transactional(readOnly = true)
    public SummaryInput readInput(UUID courseId, UUID documentId, int materialCharBudget) {
        Document document = require(courseId, documentId);
        return new SummaryInput(
                document.getSummary() != null,
                document.getStatus(),
                gatherMaterial(documentId, materialCharBudget));
    }

    // Concatenates the document's chunks in order up to the budget. Page markers are left out on
    // purpose: a summary describes the document as a whole, and page noise pulls the model toward
    // quoting locations instead of synthesizing.
    private String gatherMaterial(UUID documentId, int budget) {
        StringBuilder material = new StringBuilder();
        for (DocumentChunk chunk : chunkRepository.findByDocumentIdOrderByChunkIndex(documentId)) {
            if (material.length() >= budget) {
                break;
            }
            material.append(chunk.getContent().strip()).append("\n\n");
        }
        return material.toString();
    }

    // Replaces the glossary wholesale and stamps the document. Delete-then-insert rather than
    // diffing: regeneration stays simple and leaves no orphaned terms. Two concurrent
    // regenerations serialize here, so the later one wins instead of colliding on the unique index.
    @Transactional
    public void persist(UUID documentId, String summary, List<TermDraft> drafts) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        document.setSummary(summary);
        document.setSummaryGeneratedAt(Instant.now());

        termRepository.deleteByDocumentId(documentId);
        // Flush the delete before inserting, or Hibernate may order the inserts first and trip
        // the (document_id, term_index) unique index on regeneration.
        termRepository.flush();

        List<DocumentTerm> terms = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            DocumentTerm term = new DocumentTerm();
            term.setDocument(document);
            term.setTermIndex(i);
            term.setTerm(drafts.get(i).term());
            term.setDefinition(drafts.get(i).definition());
            terms.add(term);
        }
        termRepository.saveAll(terms);
    }

    private Document require(UUID courseId, UUID documentId) {
        return documentRepository.findByIdAndCourseSpaceId(documentId, courseId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    // What the orchestrator needs to decide whether to call the model, and with what.
    record SummaryInput(boolean alreadySummarized, DocumentStatus status, String material) { }

    // A validated glossary entry on its way to the database — already trimmed, deduped and
    // length-capped by the service, so the store does no thinking of its own.
    record TermDraft(String term, String definition) { }
}
