package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.Citation;

import java.util.List;
import java.util.UUID;

// The outcome of ChatService.prepare(): everything the caller needs before it starts talking to
// the model. Either the turn already has its answer — the confidence gate refused it, or the
// semantic cache had it — or it is answerable and carries the grounded prompt to stream against.
//
// The two settled cases are deliberately not distinguished here. From the streaming layer's point
// of view they are identical: text that exists already, emitted as a single delta, no model call,
// and the assistant turn is persisted by prepare() itself. Only the answerable case leaves work
// for completeTurn().
public record PreparedTurn(
        UUID conversationId,
        List<Citation> citations,
        List<LlmMessage> messages,
        // Non-null exactly when the turn needs nothing further from the model.
        String finalAnswer,
        // Non-null when a fresh answer is worth remembering — see CacheWrite.
        CacheWrite cacheWrite
) {

    public boolean isAnswered() {
        return finalAnswer != null;
    }

    static PreparedTurn answered(UUID conversationId, List<Citation> citations, String answer) {
        return new PreparedTurn(conversationId, citations, List.of(), answer, null);
    }

    static PreparedTurn answerable(UUID conversationId, List<Citation> citations,
                                   List<LlmMessage> messages, CacheWrite cacheWrite) {
        return new PreparedTurn(conversationId, citations, messages, null, cacheWrite);
    }

    // Carries the question's embedding forward from prepare() to completeTurn(), which is the
    // whole reason it exists: the vector was already paid for during the cache probe, so writing
    // the finished answer back into the cache costs a plain INSERT and no provider call.
    public record CacheWrite(UUID courseId, String question, float[] questionVector) { }
}
