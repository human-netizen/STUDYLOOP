package com.studyloop.backend.retrieval;

import java.util.List;

// What one provider call gives back when a question retrieved badly (Phase 18.2): the same question
// asked in other words, a passage that would have answered it, and what kind of question the model
// thinks it is.
//
// Three outputs from one call rather than three calls, because they are three readings of the same
// sentence and the expensive part is the round trip, not the tokens.
//
// Each part is separately optional, and `empty()` is what a caller gets when the stage did not run
// or the provider could not be parsed — no nulls to check at the four places this is read.
public record QueryExpansion(List<String> rewrites, String hypothetical, QueryIntent intent) {

    private static final QueryExpansion EMPTY = new QueryExpansion(List.of(), null, null);

    public QueryExpansion {
        rewrites = rewrites == null ? List.of() : List.copyOf(rewrites);
        if (hypothetical != null && hypothetical.isBlank()) {
            hypothetical = null;
        }
    }

    public static QueryExpansion empty() {
        return EMPTY;
    }

    // Whether anything usable came back. A call that returned valid JSON with nothing in it is the
    // same to every caller as a call that was never made.
    public boolean isEmpty() {
        return rewrites.isEmpty() && hypothetical == null && intent == null;
    }

    public boolean hasHypothetical() {
        return hypothetical != null;
    }
}
