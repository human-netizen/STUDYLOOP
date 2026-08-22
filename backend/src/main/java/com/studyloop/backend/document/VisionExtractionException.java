package com.studyloop.backend.document;

// A page was routed to the vision model and the vision model did not answer.
//
// Extends DocumentExtractionException, so it lands on the document as a FAILED status with a
// readable message rather than as a 500 nobody sees — ingestion is asynchronous and there is no
// request left to return an error to.
//
// **Why this fails the document instead of falling back to the text PDFBox produced.** The page was
// routed *because* that text is unusable: it is blank, or it is private-use gibberish, or it is two
// columns interleaved. Indexing it anyway produces a document that reports READY and cannot answer
// a question about those pages, with nothing anywhere saying why. The same argument Phase 14 made
// about a half-generated corpus, and the opposite of the summary rule in 8.2 — a missing summary
// leaves a fully usable document, and a missing page does not.
public class VisionExtractionException extends DocumentExtractionException {

    public VisionExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    public VisionExtractionException(String message) {
        super(message);
    }
}
