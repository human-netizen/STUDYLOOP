package com.studyloop.backend.flashcard;

import java.util.UUID;

// A flashcard id that isn't the caller's within the given course → 404 (see GlobalExceptionHandler).
public class FlashcardNotFoundException extends RuntimeException {

    public FlashcardNotFoundException(UUID cardId) {
        super("Flashcard not found: " + cardId);
    }
}
