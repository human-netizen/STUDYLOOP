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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

// One retrievable slice of a document's text. Chunks are the unit RAG search returns and
// cites; the vector embedding (Phase 4.4) is added as a column on this table later. The
// (document, chunk_index) unique constraint keeps ordering stable and makes re-ingestion
// replace-in-place rather than duplicate.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_chunks", uniqueConstraints =
        @UniqueConstraint(name = "uq_chunk_document_index", columnNames = {"document_id", "chunk_index"}))
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // 0-based position of this chunk within its document.
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    // 1-based page the chunk starts on; null if the source had no page information.
    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // Approximate token count of the content (used later for prompt-budget accounting).
    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
