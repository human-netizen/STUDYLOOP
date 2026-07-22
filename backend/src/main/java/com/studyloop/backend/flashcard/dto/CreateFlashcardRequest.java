package com.studyloop.backend.flashcard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// Saves a single card by hand — the flow behind "save this Q&A as a flashcard" (front = the
// question, back = the answer). `sourceDocumentId`/`sourcePage` are optional provenance; when a
// document id is given it must belong to the same course.
public record CreateFlashcardRequest(
        @NotBlank(message = "Front must not be blank.")
        @Size(max = 4000, message = "Front is too long.")
        String front,

        @NotBlank(message = "Back must not be blank.")
        @Size(max = 4000, message = "Back is too long.")
        String back,

        UUID sourceDocumentId,
        Integer sourcePage
) {
}
