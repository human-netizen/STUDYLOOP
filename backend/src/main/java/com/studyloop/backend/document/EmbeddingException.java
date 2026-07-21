package com.studyloop.backend.document;

// Raised when embedding fails (misconfiguration or a provider error). Caught by the
// ingestion orchestrator and recorded as a FAILED document.
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
