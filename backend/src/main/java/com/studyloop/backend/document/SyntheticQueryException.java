package com.studyloop.backend.document;

// Synthetic-query generation was asked for and could not be completed (Phase 14.1).
//
// Unlike a missing summary this is not swallowed, and the difference is what the two failures cost.
// A document with no summary is fully usable — chat, quizzes and search all work, and any member
// can generate one later from the document view. A document whose sections are missing their
// synthetic block is *silently harder to find*, for as long as it exists: the block is part of the
// text that was embedded, so writing it later means re-embedding every chunk, and nothing in the UI
// would ever say the document is in that state. Failing the ingest is visible and one re-upload
// away; the alternative is a corpus that is quietly a blend of two pipelines, which is the exact
// failure Phase 12's eval run was built to stop reporting.
public class SyntheticQueryException extends RuntimeException {

    public SyntheticQueryException(String message) {
        super(message);
    }

    public SyntheticQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
