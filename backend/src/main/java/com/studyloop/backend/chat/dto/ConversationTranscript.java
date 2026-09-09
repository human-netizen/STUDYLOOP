package com.studyloop.backend.chat.dto;

import com.studyloop.backend.chat.ChatRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A thread as a reader reopens it (Phase 28.1): every turn in order, each answer carrying the
// sources it was shown with.
//
// **`role` is on the wire rather than a boolean, and GENERAL is the reason.** A past answer given
// from general knowledge is still the assistant speaking, and it is the one turn in a transcript
// that was never grounded in the course's materials — the client marks it as such for the same
// reason `ChatRole` distinguishes it for the model. Collapsing the three roles to
// "user-or-assistant" would render that turn as though the course had said it.
public record ConversationTranscript(
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<TranscriptMessage> messages
) {

    // `citations` is empty on every USER turn, on every GENERAL turn, and on any answer stored
    // before V29 — the client renders [n] markers as plain text when the list is empty, which is
    // the honest reading of a turn whose sources were never kept.
    public record TranscriptMessage(
            UUID id,
            ChatRole role,
            String content,
            Instant createdAt,
            List<Citation> citations
    ) {
    }
}
