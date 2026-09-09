package com.studyloop.backend.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Phase 26.1 — the three properties the thread-scoped sink has to have, and one it acquired.
//
// They are tested here rather than through a chat turn because every one of them is about what
// happens when a turn is *not* the thing calling: the eval harness, the corpus watch, the search
// API and the non-streaming endpoint all reach the same four components with no scope open, and
// what they must observe is nothing at all.
class TurnProgressTest {

    // The load-bearing one. Retrieval, the reranker and the section expander all call report()
    // unconditionally, so if an unscoped call did anything — threw, allocated, logged at INFO —
    // it would do it on every search in the product, most of which are not chat turns.
    @Test
    void anUnscopedReportIsANoOp() {
        TurnProgress.report(TurnProgress.LOOKING);
        TurnProgress.report(TurnProgress.WRITING);
        // Reaching here is the assertion: nothing was scoped, so nothing could have been reported.
    }

    @Test
    void stagesReachTheSinkInOrder() {
        List<String> seen = new ArrayList<>();
        try (var ignored = TurnProgress.to(seen::add)) {
            TurnProgress.report(TurnProgress.LOOKING);
            TurnProgress.report(TurnProgress.ranking(30));
            TurnProgress.report(TurnProgress.reading(6));
            TurnProgress.report(TurnProgress.WRITING);
        }
        assertEquals(List.of("Looking for this in your materials", "Ranking 30 passages",
                "Reading 6 sources", "Writing the answer"), seen);
    }

    // Two components name the same step on purpose — the stream service the moment the request
    // lands, retrieval when the candidate searches actually start — and the client should see one
    // transition, not two identical events.
    @Test
    void theSameStageTwiceIsReportedOnce() {
        List<String> seen = new ArrayList<>();
        try (var ignored = TurnProgress.to(seen::add)) {
            TurnProgress.report(TurnProgress.LOOKING);
            TurnProgress.report(TurnProgress.LOOKING);
            TurnProgress.report(TurnProgress.WRITING);
            TurnProgress.report(TurnProgress.LOOKING);
        }
        assertEquals(List.of("Looking for this in your materials", "Writing the answer",
                "Looking for this in your materials"), seen);
    }

    // Restores rather than clears, so a nested scope cannot silence the one around it. Nothing
    // nests today; the property is here because the failure it prevents is silent — an outer turn
    // that simply stops narrating halfway through.
    @Test
    void aNestedScopeRestoresTheOuterOne() {
        List<String> outer = new ArrayList<>();
        List<String> inner = new ArrayList<>();
        try (var ignored = TurnProgress.to(outer::add)) {
            try (var alsoIgnored = TurnProgress.to(inner::add)) {
                TurnProgress.report(TurnProgress.SEARCHING);
            }
            TurnProgress.report(TurnProgress.WRITING);
        }
        assertEquals(List.of("Searching your materials"), inner);
        assertEquals(List.of("Writing the answer"), outer);
    }

    // The scope closes even when the work inside it throws, which is the case that matters: a
    // failed turn must not leave the next task on this executor thread reporting into a dead
    // emitter.
    @Test
    void aFailingTurnStillClosesItsScope() {
        List<String> seen = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = TurnProgress.to(seen::add)) {
                TurnProgress.report(TurnProgress.LOOKING);
                throw new IllegalStateException("retrieval blew up");
            }
        });
        TurnProgress.report(TurnProgress.WRITING);
        assertEquals(List.of("Looking for this in your materials"), seen);
    }

    // A sink that throws is the ordinary way a stream ends: the student closed the tab. That is a
    // reason to stop narrating, not a reason to abandon an answer already half paid for.
    @Test
    void aBrokenSinkDoesNotFailTheTurn() {
        try (var ignored = TurnProgress.to(stage -> {
            throw new IllegalStateException("client disconnected");
        })) {
            TurnProgress.report(TurnProgress.LOOKING);
        }
        // Reaching here is the assertion.
        assertTrue(true);
    }
}
