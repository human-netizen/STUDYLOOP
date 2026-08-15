package com.studyloop.backend.document;

// The model failed to produce a usable summary (unconfigured provider, request error, or output
// that didn't parse) → 502 (see GlobalExceptionHandler). Thrown only on the on-demand path; the
// ingestion path catches it and leaves the document READY without a summary.
public class SummaryGenerationException extends RuntimeException {

    public SummaryGenerationException(String message) {
        super(message);
    }

    public SummaryGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
