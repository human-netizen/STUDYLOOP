package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.Citation;

import java.util.List;
import java.util.UUID;

// The outcome of ChatService.prepare(): everything the streaming layer needs before it starts
// talking to the model. Either the turn is answerable (carry the grounded prompt + citations to
// stream against) or it was refused by the confidence gate (carry the canned refusal text, no
// model call). The user turn has already been persisted; the assistant turn is saved afterwards.
public record PreparedTurn(
        UUID conversationId,
        List<Citation> citations,
        List<LlmMessage> messages,
        boolean refused,
        String refusalText
) {

    static PreparedTurn answerable(UUID conversationId, List<Citation> citations, List<LlmMessage> messages) {
        return new PreparedTurn(conversationId, citations, messages, false, null);
    }

    static PreparedTurn refused(UUID conversationId, String refusalText) {
        return new PreparedTurn(conversationId, List.of(), List.of(), true, refusalText);
    }
}
