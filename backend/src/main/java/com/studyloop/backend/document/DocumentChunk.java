package com.studyloop.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    // What this chunk's vector was made from (Phase 17.2). Visual chunks are appended after the
    // text chunks of the same document, so they continue the same index sequence and the unique
    // constraint above still means what it meant.
    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, length = 10)
    private ChunkModality modality = ChunkModality.TEXT;

    // 1-based page the chunk starts on; null if the source had no page information.
    @Column(name = "page_number")
    private Integer pageNumber;

    // 1-based page the chunk's text runs to, which is usually pageNumber and occasionally the one
    // after it. Both ends are recorded because a citation should be able to say where a passage
    // finishes, and because grading retrieval against "the page the answer is on" needs to know
    // whether a chunk covered that page rather than merely started near it (Phase 13).
    @Column(name = "page_end")
    private Integer pageEnd;

    // The heading trail this chunk sits under — "Chapter 4 > 4.2 Skiplists". Null for text that
    // stated no structure, and for every chunk written before Phase 13.
    @Column(name = "section_path", columnDefinition = "text")
    private String sectionPath;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // What is embedded and lexically indexed: the context header followed by `content`. Null means
    // "identical to content", which is what the SQL's coalesce reads and what every pre-13 row has.
    //
    // The split exists because content_tsv used to be generated from `content`, so anything added
    // to help the dense half would have landed in the lexical index too — every chunk in a chapter
    // sharing its heading text, ts_rank rewarding it, and lexical precision falling. Two columns,
    // one displayed and one indexed, is the fix.
    @Column(name = "embed_text", columnDefinition = "text")
    private String embedText;

    // Token count of `content` — the passage itself, not the context header in front of it — from
    // a real byte-pair encoder as of Phase 13.1 rather than the chars/4 estimate it used to be.
    // Same text the chunking ceiling was applied to, so the two numbers can be compared.
    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
