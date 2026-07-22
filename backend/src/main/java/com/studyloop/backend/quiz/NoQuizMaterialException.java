package com.studyloop.backend.quiz;

// The chosen documents have no ingested (READY) content to build a quiz from → 400 (see
// GlobalExceptionHandler). A client-side problem: pick documents that have finished ingesting.
public class NoQuizMaterialException extends RuntimeException {

    public NoQuizMaterialException(String message) {
        super(message);
    }
}
