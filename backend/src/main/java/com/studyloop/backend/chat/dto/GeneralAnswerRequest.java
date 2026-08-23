package com.studyloop.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// "Answer it from general knowledge instead" (Phase 20.2) — the one button on a refusal screen.
//
// `conversationId` is required, unlike on a chat turn, and the asymmetry is the feature: this
// endpoint is only reachable from a refusal, and a refusal always happened inside a conversation
// that already holds the question. Requiring it means the escape hatch cannot be used as a
// general-purpose ungrounded chat endpoint that happens to live inside a course.
//
// `questionEventId` is the refusal being escalated. Optional — the answer is given either way —
// but it is what turns the escape hatch into a measurement, so the client always sends it.
public record GeneralAnswerRequest(
        @NotBlank(message = "Question must not be blank.")
        @Size(max = 4000, message = "Question is too long.")
        String question,

        @NotNull(message = "A conversation is required.")
        UUID conversationId,

        UUID questionEventId
) {
}
