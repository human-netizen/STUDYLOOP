package com.studyloop.backend.flashcard;

// The model failed to produce usable flashcards (unconfigured provider, request error, or output
// that didn't parse) → 502 (see GlobalExceptionHandler). Distinct from NoFlashcardMaterialException,
// which is the caller's fault (the document has nothing to build cards from).
public class FlashcardGenerationException extends RuntimeException {

    public FlashcardGenerationException(String message) {
        super(message);
    }

    public FlashcardGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
