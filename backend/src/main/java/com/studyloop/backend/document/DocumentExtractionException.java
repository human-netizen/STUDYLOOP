package com.studyloop.backend.document;

// Raised inside the ingestion pipeline when a PDF can't be read or yields no usable text.
// It's caught by the orchestrator and recorded as a FAILED document (never reaches the web
// layer), so its message is written to be shown to the uploader.
public class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message) {
        super(message);
    }

    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
