package com.studyloop.backend.document;

// Lifecycle of an uploaded document as it moves through the ingestion pipeline. Phase 4.1
// only ever sets UPLOADED; the async pipeline (4.2) drives the rest, ending in READY or
// FAILED.
public enum DocumentStatus {
    UPLOADED,
    EXTRACTING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED,
    // Phase 27.3 — in the library, out of the corpus. The row, the bytes, the chunks and the
    // vectors all stay; only this value changes, and un-retiring is the same one-field update
    // back.
    //
    // **It costs nothing to enforce, and that is a fact about SQL already written rather than a
    // hope.** Every one of the six candidate branches in ChunkSearchRepository filters
    // `d.status = 'READY'`, and QuizService and FlashcardService gate on it too — so a value that
    // is not READY is already invisible to search, quiz generation and flashcard generation, with
    // no query changed anywhere and no migration. The column is varchar(20) with no check
    // constraint, so the enum widens on its own.
    //
    // Deliberately *not* a terminal status like FAILED: a document reaches this because somebody
    // chose it, and the state it is in is "fine, just not answering questions right now".
    RETIRED;

    // Documents in this state are the corpus. Written once so a caller reads the same rule the
    // retrieval SQL applies, rather than re-deriving "not FAILED and not RETIRED and finished".
    public boolean isSearchable() {
        return this == READY;
    }
}
