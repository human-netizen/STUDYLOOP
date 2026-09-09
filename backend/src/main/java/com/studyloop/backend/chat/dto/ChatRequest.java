package com.studyloop.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// A chat turn from the client. `conversationId` is null to start a fresh thread, or the id of
// an existing thread to continue it (carrying prior turns as context).
public record ChatRequest(
        @NotBlank(message = "Question must not be blank.")
        @Size(max = 4000, message = "Question is too long.")
        String question,

        UUID conversationId,

        // Phase 28.2 — which documents to search. Null or empty means the whole course, which is
        // every caller that existed before this field did, and is still the common case: a
        // question about the course is a question about the course.
        //
        // The ids are not validated against the course here, and they do not need to be. Every
        // branch of retrieval already carries `course_space_id = ?` and 16.3's visibility clause,
        // so an id from another course — or another member's private note — narrows the search to
        // nothing rather than widening it to something. The scope can only ever be an
        // intersection.
        @Size(max = 50, message = "Too many documents selected.")
        List<UUID> documentIds
) {

    // A turn over the whole course, which is what this record meant before Phase 28.2 added a
    // third component. Kept as a constructor rather than left to callers to pass `null` because
    // "no scope" is the default rather than a missing argument — and because the JSON path never
    // uses it, so this is the one place the omission can be spelled.
    public ChatRequest(String question, UUID conversationId) {
        this(question, conversationId, null);
    }
}
