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
        // True when the thread came from a question the assistant refused, rather than from
        // someone simply asking the class.
        boolean fromRefusal,
        Instant createdAt,
        Instant updatedAt
) {

    public static ForumThreadSummary from(ForumThread thread, int answerCount) {
        return new ForumThreadSummary(
                thread.getId(),
                thread.getTitle(),
                thread.getStatus(),
                thread.getCreatedBy().getDisplayName(),
                answerCount,
                thread.getAnswerDocumentId() != null,
                thread.getQuestionEventId() != null,
                thread.getCreatedAt(),
                thread.getUpdatedAt());
    }
}
