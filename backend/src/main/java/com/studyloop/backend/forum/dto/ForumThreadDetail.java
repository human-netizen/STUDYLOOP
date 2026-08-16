package com.studyloop.backend.forum.dto;

import com.studyloop.backend.forum.ForumThread;
import com.studyloop.backend.forum.ForumThreadStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// One thread and its replies.
//
// `canAccept` is answered here rather than left to the client to work out from a role: accepting
// writes into the course's corpus, so who may do it is a backend rule, and the button should
// appear for exactly the people the endpoint will let through.
public record ForumThreadDetail(
        UUID id,
        String title,
        String body,
        ForumThreadStatus status,
        String authorName,
        UUID questionEventId,
        UUID acceptedAnswerId,
        boolean inCorpus,
        boolean canAccept,
        List<ForumAnswerResponse> answers,
        Instant createdAt,
        Instant updatedAt
) {

    public static ForumThreadDetail from(ForumThread thread, List<ForumAnswerResponse> answers,
                                         boolean canAccept) {
        return new ForumThreadDetail(
                thread.getId(),
                thread.getTitle(),
                thread.getBody(),
                thread.getStatus(),
                thread.getCreatedBy().getDisplayName(),
                thread.getQuestionEventId(),
                thread.getAcceptedAnswerId(),
                thread.getAnswerDocumentId() != null,
                canAccept,
                answers,
                thread.getCreatedAt(),
                thread.getUpdatedAt());
    }
}
