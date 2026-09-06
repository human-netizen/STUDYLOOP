package com.studyloop.backend.document;

// Wraps a failure while storing or reading document bytes — a filesystem error under
// FilesystemDocumentStorage, an HTTP error under SupabaseDocumentStorage → surfaced as 500.
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
