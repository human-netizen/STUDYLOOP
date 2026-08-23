package com.studyloop.backend.forum.dto;

import com.studyloop.backend.forum.ForumAnswer;
import com.studyloop.backend.forum.ForumAnswerAuthor;
import com.studyloop.backend.forum.ForumThread;

import java.time.Instant;
import java.util.UUID;

// One reply, with its author's display name.
//
// Names are on the wire here, and deliberately — note the contrast with the confusion report,
// which strips them. That page is a measurement of the class, and who asked is not the
// instructor's business; this is a conversation, and an answer nobody is willing to sign is not
// worth accepting into the course's materials.
// `authorKind` is on the wire and the client renders the two differently, which is not styling:
// the assistant's reply is unaccepted, unacceptable and unsigned, and a reader deciding whether to
// trust a paragraph in a study forum is entitled to know which of those it is looking at.
public record ForumAnswerResponse(
        UUID id,
        String body,
        ForumAnswerAuthor authorKind,
        // Null for the assistant's reply. Deliberately null rather than a friendly placeholder
        // like "StudyLoop" — a name in this field means a person in the course said this, and the
        // label the UI shows instead is a client's wording, not a fact from the database.
        String authorName,
        boolean accepted,
        // The upload that made this thread answerable (20.1), null on a person's reply. Together
        // with topSimilarity it is the demonstration: this question was refused, then this
        // document arrived, and the same gate that refused it let this through.
        UUID sourceDocumentId,
        Double topSimilarity,
        Instant createdAt
) {

    public static ForumAnswerResponse from(ForumAnswer answer, ForumThread thread) {
        return new ForumAnswerResponse(
                answer.getId(),
                answer.getBody(),
                answer.getAuthorKind(),
                answer.getCreatedBy() == null ? null : answer.getCreatedBy().getDisplayName(),
                answer.getId().equals(thread.getAcceptedAnswerId()),
                answer.getSourceDocumentId(),
                answer.getTopSimilarity(),
                answer.getCreatedAt());
    }
}
