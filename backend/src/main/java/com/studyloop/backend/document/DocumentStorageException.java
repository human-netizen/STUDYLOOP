package com.studyloop.backend.document;

// Wraps a filesystem failure while storing or reading document bytes → surfaced as 500.
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
