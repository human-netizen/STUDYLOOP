package com.studyloop.backend.forum.dto;

import com.studyloop.backend.forum.ForumAnswer;
import com.studyloop.backend.forum.ForumThread;

import java.time.Instant;
import java.util.UUID;

// One reply, with its author's display name.
//
// Names are on the wire here, and deliberately — note the contrast with the confusion report,
// which strips them. That page is a measurement of the class, and who asked is not the
// instructor's business; this is a conversation, and an answer nobody is willing to sign is not
// worth accepting into the course's materials.
public record ForumAnswerResponse(
        UUID id,
        String body,
        String authorName,
        boolean accepted,
        Instant createdAt
) {

    public static ForumAnswerResponse from(ForumAnswer answer, ForumThread thread) {
        return new ForumAnswerResponse(
                answer.getId(),
                answer.getBody(),
                answer.getCreatedBy().getDisplayName(),
                answer.getId().equals(thread.getAcceptedAnswerId()),
                answer.getCreatedAt());
    }
}
