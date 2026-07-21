package com.studyloop.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// A chat turn from the client. `conversationId` is null to start a fresh thread, or the id of
// an existing thread to continue it (carrying prior turns as context).
public record ChatRequest(
        @NotBlank(message = "Question must not be blank.")
        @Size(max = 4000, message = "Question is too long.")
        String question,

        UUID conversationId
) {
}
