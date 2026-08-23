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
        // Set when this student has asked this course the same thing before (Phase 20.3). Null on
        // almost every turn.
        AskedBefore askedBefore
) {
}
