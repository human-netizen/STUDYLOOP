package com.studyloop.backend.forum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// Opening a thread. `title` is the question itself — when the client escalates a refusal it sends
// back the exact wording chat refused, so the thread reads as the question that was asked.
//
// `questionEventId` links the thread to that refusal. Optional, because a member may also just
// ask the class something outright; ignored when it names a question from another course.
public record CreateThreadRequest(
        @NotBlank(message = "A question is required.")
        @Size(max = 300, message = "Keep the question to 300 characters; use the details for more.")
        String title,

        @Size(max = 4000, message = "That's too long for one post.")
        String body,

        UUID questionEventId
) {
}
