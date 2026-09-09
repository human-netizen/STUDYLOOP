package com.studyloop.backend.chat.dto;

import java.util.List;
import java.util.UUID;

// The assistant's reply. `conversationId` echoes the thread (newly created when the request
// omitted one) so the client can send it back to continue. `answer` contains inline [n]
// markers that refer to `citations` by their `index`.
public record ChatResponse(
        UUID conversationId,
        String answer,
        List<Citation> citations,
        // Set only when the confidence gate refused: the id of the recorded question, which the
        // client passes back to open a forum thread against this exact refusal (Phase 9.2), or to
        // ask for the same question from general knowledge instead (Phase 20.2).
        UUID questionEventId,
        // Phase 28.3 — the handle a verdict on this answer attaches to. Set on every turn the
        // question log recorded, which is all of them while logging is on; null when it is off, and
        // the client hides the feedback control rather than offering one that cannot be stored.
        //
        // On a refusal this is the same id as `questionEventId` above. It is a separate field
        // because they are separate questions: one asks "may this be escalated", the other "what
        // is this verdict about", and only the first is refusal-shaped.
        UUID answerEventId,
        // Set when this student has asked this course the same thing before (Phase 20.3). Null on
        // almost every turn.
        AskedBefore askedBefore
) {
}
