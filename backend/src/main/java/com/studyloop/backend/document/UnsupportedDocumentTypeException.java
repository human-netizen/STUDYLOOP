package com.studyloop.backend.document;

// The uploaded file's content type isn't one we can ingest (only PDF for now) → 415.
public class UnsupportedDocumentTypeException extends RuntimeException {

    public UnsupportedDocumentTypeException(String contentType) {
        super("Unsupported document type: " + (contentType == null ? "unknown" : contentType)
                + ". Only PDF files are accepted.");
    }
}
