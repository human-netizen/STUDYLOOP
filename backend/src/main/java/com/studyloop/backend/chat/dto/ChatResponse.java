package com.studyloop.backend.chat.dto;

import java.util.List;
import java.util.UUID;

// The assistant's reply. `conversationId` echoes the thread (newly created when the request
// omitted one) so the client can send it back to continue. `answer` contains inline [n]
// markers that refer to `citations` by their `index`.
public record ChatResponse(
        UUID conversationId,
        String answer,
        List<Citation> citations
) {
}
