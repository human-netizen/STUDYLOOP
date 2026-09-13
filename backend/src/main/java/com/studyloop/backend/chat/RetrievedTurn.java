package com.studyloop.backend.chat;

import com.studyloop.backend.chat.PreparedTurn.CacheWrite;
import com.studyloop.backend.chat.dto.AskedBefore;
import com.studyloop.backend.chat.dto.Citation;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// What the middle phase of a turn found (Phase 26.2): the outcome of the cache probe, retrieval
// and the confidence gate, with **nothing written yet**.
//
// The split is the whole content of this record. Everything here was produced outside a
// transaction and outside a database connection — the two provider calls a turn makes before it
// generates, the embedding and the cross-encoder, happen while this is being built — and the
// writes it implies are applied afterwards by `recordTurn` in a short transaction of its own.
public record RetrievedTurn(
        Outcome outcome,
        List<Citation> citations,
        // The grounded prompt to stream against. Empty unless the outcome is GROUNDED.
        List<LlmMessage> messages,
        // The numbered passages on their own, which is the same text the system prompt above
        // carries. Phase 26.3 hands this back as the `tool` message when the model asked to search
        // rather than being told to.
        String sources,
        // The answer that already exists: the cached one, or the gate's refusal. Null when the
        // model still has to write it.
        String settledAnswer,
        CacheWrite cacheWrite,
        AskedBefore askedBefore,
        // The question's embedding, whichever half of the turn paid for it, so analytics does not
        // ask the provider for a third copy of the same string.
        float[] questionVector,
        Double topSimilarity,
        // The documents the answer draws on, for the lecture attribution on the question log.
        Set<UUID> documentIds,
        // Phase 23.2 - the narrowing retrieval applied because the question named a week or a
        // kind of material, as a sentence to show the reader. Null on almost every turn, and
        // null on a cache hit for a stronger reason than rarity: no retrieval ran, so there was
        // nothing to narrow.
        String scopeNote
) {

    public enum Outcome {
        // The semantic cache already held an answer to this question.
        CACHED,
        // The confidence gate refused: nothing in the materials is close enough to answer from.
        REFUSED,
        // Retrieval found sources and the model still has to write the answer.
        GROUNDED
    }

    public boolean isSettled() {
        return settledAnswer != null;
    }
}
