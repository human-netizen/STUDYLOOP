package com.studyloop.backend.forum.dto;

import com.studyloop.backend.forum.ForumThread;
import com.studyloop.backend.forum.ForumThreadStatus;

import java.time.Instant;
import java.util.UUID;

// A row in the forum list. `inCorpus` is the whole point of the feature made visible: the accepted
// answer is now material the assistant can retrieve, not just a post somebody has to scroll to.
public record ForumThreadSummary(
        UUID id,
        String title,
        ForumThreadStatus status,
        String authorName,
        int answerCount,
        boolean inCorpus,
        // Whether the assistant has already replied here (20.1). On the list view this is the
        // difference between a thread nobody has looked at and one that has an answer waiting for
        // somebody to confirm or correct it.
        boolean assistantAnswered,
        // True when the thread came from a question the assistant refused, rather than from
        // someone simply asking the class.
        boolean fromRefusal,
        Instant createdAt,
        Instant updatedAt
) {

    public static ForumThreadSummary from(ForumThread thread, int answerCount,
                                          boolean assistantAnswered) {
        return new ForumThreadSummary(
                thread.getId(),
                thread.getTitle(),
                thread.getStatus(),
                thread.getCreatedBy().getDisplayName(),
                answerCount,
                thread.getAnswerDocumentId() != null,
                assistantAnswered,
                thread.getQuestionEventId() != null,
                thread.getCreatedAt(),
                thread.getUpdatedAt());
    }
}
