package com.studyloop.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// A reader's verdict on one answer (Phase 28.3).
//
// **The citations come from the client, and that is deliberate rather than lazy.** The server could
// re-run retrieval for this question and record what it finds, and that would be a different fact:
// the corpus moves, documents are retired, stages get switched on. What makes a complaint
// actionable is the passages that were *on screen* when somebody decided the answer was wrong, so
// the screen is the thing asked.
//
// Nothing here is trusted for authorization. `answerEventId` is checked against the course before
// it is stored, so a client cannot attach a verdict to another course's question, and the citations
// are opaque display data that no query joins on.
public record AnswerFeedbackRequest(
        // Null when question logging is off — the verdict is still stored, with its question text
        // and its citations, and only the join back to the event is lost.
        UUID answerEventId,

        @NotNull(message = "A verdict is required.")
        Boolean helpful,

        @Size(max = 2000, message = "Reason is too long.")
        String reason,

        @NotBlank(message = "The question is required.")
        @Size(max = 4000, message = "Question is too long.")
        String question,

        List<Citation> citations
) {
}
