package com.studyloop.backend.document;

// The document has no ingested text to summarize — it hasn't reached READY, or extraction found
// nothing → 400 (see GlobalExceptionHandler). The caller's problem: wait for ingestion to finish.
public class NoSummaryMaterialException extends RuntimeException {

    public NoSummaryMaterialException(String message) {
        super(message);
    }
}
