package com.studyloop.backend.chat;

import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.retrieval.DocumentScope;

import java.util.List;
import java.util.UUID;

// Everything a chat turn read from the database before it spoke to anybody (Phase 26.2).
//
// It exists because the turn was split into three phases with a rule between them: **reads, then
// the provider calls, then writes.** `prepare()` used to interleave all three inside one
// transaction, which cost round trips — Hibernate flushes before a query, so a `save` between two
// selects buys its own trip — and, worse, held a pooled Supabase connection open across two
// network calls to a model provider. Five connections in the pool means the sixth concurrent
// question waited on a connection rather than on an answer.
//
// The `Membership` is carried rather than the ids alone, and that is the point of it: it is the
// proof that this actor may ask this course anything, and retrieval takes the proof instead of
// re-deriving it (`RetrievalService.searchAsMember`). The two ids beside it are read off the same
// object at construction, so they cannot disagree with it.
public record TurnContext(
        Membership member,
        UUID courseId,
        UUID actorId,
        // The thread this turn belongs to. Always set — a turn that opened a new thread created it
        // during the read phase, because the id has to be on the `meta` event and `meta` is sent
        // before the answer.
        UUID conversationId,
        String question,
        Language language,
        // Whether this turn started the thread. The semantic cache reads it: a follow-up ("what
        // about the second one?") is meaningless without the turns before it, so only an opening
        // question is cacheable.
        boolean opensThread,
        // Prior turns, oldest first, already capped and already translated into provider messages.
        // Does **not** include the question this turn is about: the reads happen before the write
        // that saves it, so the caller appends it. That ordering is what removed a flush.
        List<LlmMessage> history,

        // Phase 28.2 — the documents this turn may be answered from, or `WHOLE_COURSE`.
        //
        // It rides on the context rather than being passed alongside it because the streaming path
        // hands this object between three calls, and a scope that travelled separately would be a
        // scope one of those calls could forget. The tool-calling path (26.3) inherits it for the
        // same reason: the model chooses *what to search for*, and the reader has already chosen
        // *where*.
        DocumentScope scope
) {

    public static TurnContext of(Membership member, UUID conversationId, String question,
                                 Language language, boolean opensThread, List<LlmMessage> history,
                                 DocumentScope scope) {
        return new TurnContext(member, member.getCourseSpace().getId(), member.getUser().getId(),
                conversationId, question, language, opensThread, history, scope);
    }
}
