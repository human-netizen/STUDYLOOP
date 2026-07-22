package com.studyloop.backend.flashcard.dto;

import com.studyloop.backend.flashcard.Flashcard;

import java.time.Instant;
import java.util.UUID;

// A flashcard as returned to its owner.
public record FlashcardResponse(
        UUID id,
        String front,
        String back,
        UUID sourceDocumentId,
        Integer sourcePage,
        Instant createdAt
) {

    public static FlashcardResponse from(Flashcard card) {
        return new FlashcardResponse(
                card.getId(),
                card.getFront(),
                card.getBack(),
                card.getSourceDocumentId(),
                card.getSourcePage(),
                card.getCreatedAt());
    }
}
