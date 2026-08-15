package com.studyloop.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

// One entry in a document's auto-generated glossary (Phase 8.2): a term the material relies on
// and a one-line definition drawn from that material. Stored as rows rather than a JSON blob on
// `documents` so a course-wide glossary and the Phase 9 term analytics are plain queries.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_terms", uniqueConstraints =
        @UniqueConstraint(name = "uq_document_terms_doc_index", columnNames = {"document_id", "term_index"}))
public class DocumentTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // 0-based position in the model's output, which runs roughly most- to least-central.
    @Column(name = "term_index", nullable = false)
    private int termIndex;

    @Column(nullable = false, length = 120)
    private String term;

    @Column(nullable = false, columnDefinition = "text")
    private String definition;
}
