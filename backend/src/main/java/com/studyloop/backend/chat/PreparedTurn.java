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
        // Phase 28.3 — the logged question this answer belongs to, on **every** outcome that was
        // logged at all: grounded, refused, and served from the cache.
        //
        // **A second id rather than widening the one above, and the reason is that the first one
        // means something narrower than its name.** `questionEventId` is the refusal handle: the
        // client tests it for null to decide whether to offer "ask the class" and "answer from
        // general knowledge", so setting it on a grounded answer would put an escalation button
        // under every correct answer in the product. What 28.3 needs is a different question —
        // which answer is this verdict about — and it has a different answer on every turn.
        UUID answerEventId,
        // Non-null when this student has asked this course the same thing before (20.3). It rides
        // along on all three outcomes, because a repeat is a repeat whether the answer came from
        // the model, the cache or the gate — and the refused one is the most worth saying out loud.
        AskedBefore askedBefore,
        // Phase 23.2 - what retrieval narrowed this turn to, when the question named a week or a
        // kind of material and the course had some. It rides the meta event beside the citations
        // for the reason 28.2's chips are on screen: a search that quietly stopped looking at
        // eleven of the fourteen documents has changed the answer, and the reader is the one
        // person who can tell whether that was what they meant.
        String scopeNote
) {

    public boolean isAnswered() {
        return finalAnswer != null;
    }

    static PreparedTurn answered(UUID conversationId, List<Citation> citations, String answer,
                                 AskedBefore askedBefore, UUID answerEventId, String scopeNote) {
        return new PreparedTurn(conversationId, citations, List.of(), answer, null, null,
                answerEventId, askedBefore, scopeNote);
    }

    static PreparedTurn refused(UUID conversationId, String answer, UUID questionEventId,
                                AskedBefore askedBefore, String scopeNote) {
        // The refusal handle and the feedback handle are the same row here, and that is the one
        // outcome where they coincide: the refusal *is* the logged event.
        return new PreparedTurn(conversationId, List.of(), List.of(), answer, null, questionEventId,
                questionEventId, askedBefore, scopeNote);
    }

    static PreparedTurn answerable(UUID conversationId, List<Citation> citations,
                                   List<LlmMessage> messages, CacheWrite cacheWrite,
                                   AskedBefore askedBefore, UUID answerEventId, String scopeNote) {
        return new PreparedTurn(conversationId, citations, messages, null, cacheWrite, null,
                answerEventId, askedBefore, scopeNote);
    }

    // Carries the question's embedding forward from prepare() to completeTurn(), which is the
    // whole reason it exists: the vector was already paid for during the cache probe, so writing
    // the finished answer back into the cache costs a plain INSERT and no provider call.
    public record CacheWrite(UUID courseId, String question, float[] questionVector) { }
}
