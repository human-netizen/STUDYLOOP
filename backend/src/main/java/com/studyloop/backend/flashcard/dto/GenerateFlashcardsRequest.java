package com.studyloop.backend.flashcard.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Asks the server to generate a deck of flashcards from one READY document. `count` is how many
// cards to produce (the service defaults and clamps it).
public record GenerateFlashcardsRequest(
        @NotNull(message = "documentId is required.")
        UUID documentId,

        @Min(value = 1, message = "Generate at least one card.")
        @Max(value = 30, message = "At most 30 cards at a time.")
        Integer count
) {
}
