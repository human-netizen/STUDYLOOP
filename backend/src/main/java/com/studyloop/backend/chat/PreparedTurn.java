package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.AskedBefore;
import com.studyloop.backend.chat.dto.Citation;

import java.util.List;
import java.util.UUID;

// The outcome of ChatService.prepare(): everything the caller needs before it starts talking to
// the model. Either the turn already has its answer — the confidence gate refused it, or the
// semantic cache had it — or it is answerable and carries the grounded prompt to stream against.
//
// The two settled cases are still not distinguished for the purpose of *streaming* them: both are
// text that exists already, emitted as a single delta, no model call, with the assistant turn
// persisted by prepare() itself. Only the answerable case leaves work for completeTurn().
//
// A refusal does carry one thing a cache hit cannot — the id of the question_events row it just
// wrote — because that is what "ask the class" attaches a forum thread to (Phase 9.2). It rides
// along in the meta event; the stream's control flow never looks at it.
public record PreparedTurn(
        UUID conversationId,
        List<Citation> citations,
        List<LlmMessage> messages,
        // Non-null exactly when the turn needs nothing further from the model.
        String finalAnswer,
        // Non-null when a fresh answer is worth remembering — see CacheWrite.
        CacheWrite cacheWrite,
        // Non-null only on a refusal, and only while question logging is switched on.
        UUID questionEventId,
        // Non-null when this student has asked this course the same thing before (20.3). It rides
        // along on all three outcomes, because a repeat is a repeat whether the answer came from
        // the model, the cache or the gate — and the refused one is the most worth saying out loud.
        AskedBefore askedBefore
) {

    public boolean isAnswered() {
        return finalAnswer != null;
    }

    static PreparedTurn answered(UUID conversationId, List<Citation> citations, String answer,
                                 AskedBefore askedBefore) {
        return new PreparedTurn(conversationId, citations, List.of(), answer, null, null, askedBefore);
    }

    static PreparedTurn refused(UUID conversationId, String answer, UUID questionEventId,
                                AskedBefore askedBefore) {
        return new PreparedTurn(conversationId, List.of(), List.of(), answer, null, questionEventId,
                askedBefore);
    }

    static PreparedTurn answerable(UUID conversationId, List<Citation> citations,
                                   List<LlmMessage> messages, CacheWrite cacheWrite,
                                   AskedBefore askedBefore) {
        return new PreparedTurn(conversationId, citations, messages, null, cacheWrite, null,
                askedBefore);
    }

    // Carries the question's embedding forward from prepare() to completeTurn(), which is the
    // whole reason it exists: the vector was already paid for during the cache probe, so writing
    // the finished answer back into the cache costs a plain INSERT and no provider call.
    public record CacheWrite(UUID courseId, String question, float[] questionVector) { }
}
