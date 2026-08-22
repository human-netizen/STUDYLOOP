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

// One block a vision model read off a photographed note, with the confidence it reported and
// whether that was enough to index it (Phase 16.3).
//
// **Why this is a table and not a log line.** The blocks that were *not* indexed are the reason it
// exists. A note whose middle third the model could not read is still a useful note, but only if
// the student can see which third — otherwise the gap is silent, and a silent gap in your own
// revision notes is the worst kind, because you revise from what is there and never learn what
// is not. The review view reads these rows and shows the dropped text next to what was kept.
//
// The indexed blocks are stored too, even though their text is also in `document_chunks`. Chunks
// are the chunker's units, not the model's: a short heading and the paragraph under it are one
// chunk with one identity, and the confidences that produced them are two different numbers. This
// table is the reading; that one is the index built from it.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_note_blocks", uniqueConstraints =
        @UniqueConstraint(name = "uq_note_block_document_ordinal",
                columnNames = {"document_id", "ordinal"}))
public class DocumentNoteBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // Reading order on the page, from zero. What makes "the second block" mean something stable
    // to a reviewer looking at the photo next to the transcription.
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // What the model said about its own reading, 0 to 1. Stored raw rather than bucketed into
    // high/low, because the threshold is configuration and a reviewer disagreeing with it should
    // be able to see the number the decision was made from.
    @Column(nullable = false)
    private double confidence;

    // The decision, recorded rather than recomputed. Reading it back off today's threshold would
    // answer what the current configuration *would* do, not what this note's corpus was built
    // with — the same distinction `documents.vision_pages` exists to preserve.
    @Column(nullable = false)
    private boolean indexed;
}
