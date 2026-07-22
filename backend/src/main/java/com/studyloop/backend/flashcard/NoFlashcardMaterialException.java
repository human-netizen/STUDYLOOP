package com.studyloop.backend.flashcard;

// The chosen document has no ingested (READY) content to build cards from → 400 (see
// GlobalExceptionHandler). A client-side problem: pick a document that has finished ingesting.
public class NoFlashcardMaterialException extends RuntimeException {

    public NoFlashcardMaterialException(String message) {
        super(message);
    }
}
