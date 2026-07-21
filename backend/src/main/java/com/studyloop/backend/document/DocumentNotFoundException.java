package com.studyloop.backend.document;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("No document with id: " + id);
    }
}
