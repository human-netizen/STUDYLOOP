package com.studyloop.backend.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Where a chat turn has got to, reported by whichever step is running (Phase 26.1).
//
// **The IngestionProgress idiom from 25.2, applied to the other slow thing in the product**, and
// for the same reason it was chosen there. Two of the four steps that report are `RerankStage.apply`
// and `SectionExpander.expand`, deep inside retrieval — and both are also called by the search API,
// the eval harness, the corpus-watch forum answerer and the video script builder, none of which has
// an `SseEmitter` and none of which should learn what one is. Widening those signatures to carry a
// chat concern would put a streaming callback in the eval harness.
//
// The three correctness properties are the ones that made it work for ingestion:
//
//   * **the scope restores rather than clears**, so a nested scope cannot silence its caller;
//   * **an unscoped report is a no-op**, which is what keeps this diff small — the non-streaming
//     `POST /chat`, every existing test and all four non-chat callers behave exactly as they did,
//     because nothing opened a scope and `report` returns immediately;
//   * **the unit of work is one thread for its whole life.** `ChatStreamService.run` is one task
//     on one `chatStreamExecutor` thread and the whole turn runs inside it, so a thread-local is
//     not a compromise here — it is precisely the scope of one turn.
//
// **A sentence, and deliberately no percentage.** 25.2 gives ingestion a bar because ingestion
// knows its denominator: it is counting pages and it knows how many there are. A chat turn has no
// denominator. A bar advancing at a rate nothing can predict, from a total nobody knows, invites
// the reader to extrapolate an arrival time out of a number that was invented — so the event
// carries the stage and nothing else, and the honesty of 25.2's bar is the reason this one does
// not get one.
//
// **The DEBUG line is the deliverable, not the SSE event.** Every transition is logged with the
// milliseconds elapsed since the turn opened, which is the first time this project can answer
// "where do the seconds go" from a log line rather than from a count of call sites read off the
// code. Switch it on with `logging.level.com.studyloop.backend.chat.TurnProgress=DEBUG`.
public final class TurnProgress {

    private static final Logger log = LoggerFactory.getLogger(TurnProgress.class);

    // The stages, worded for a student rather than for a developer. They are constants here
    // rather than strings at the call sites because the wording is a product decision and the
    // call sites are in four different packages.
    public static final String LOOKING = "Looking for this in your materials";
    public static final String WRITING = "Writing the answer";
    // The two the tool-calling path adds (26.3). The first is emitted before the model has decided
    // anything, because something has to be on screen while it decides; the second is ZenLearn's
    // `on_tool_start` in one line — the moment the model asks for the corpus.
    public static final String THINKING = "Thinking about your question";
    public static final String SEARCHING = "Searching your materials";

    private static final ThreadLocal<Turn> CURRENT = new ThreadLocal<>();

    private TurnProgress() {
    }

    public static String ranking(int passages) {
        return "Ranking " + passages + (passages == 1 ? " passage" : " passages");
    }

    public static String reading(int sources) {
        return "Reading " + sources + (sources == 1 ? " source" : " sources");
    }

    // Directs this thread's reports at a sink for the length of one turn. Restores whatever was in
    // force rather than clearing, so a nested scope cannot silence its caller's reporting.
    public static Scope to(Sink sink) {
        Turn previous = CURRENT.get();
        CURRENT.set(new Turn(sink, System.nanoTime()));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    // Names the step that is now running. Silently does nothing when no scope is open.
    //
    // **A failing sink cannot fail the turn**, which is the one way this differs from ingestion's.
    // The sink here writes to a live SSE connection, and the ordinary way that fails is a student
    // closing the tab — a reason to stop reporting, not a reason to abandon an answer that is
    // already half paid for. The exception is swallowed here rather than at the four call sites,
    // so no caller has to remember.
    public static void report(String stage) {
        Turn turn = CURRENT.get();
        if (turn == null || stage.equals(turn.lastStage)) {
            // **The same stage twice is not a transition.** Two components legitimately name the
            // same step - the stream service says "looking" the instant the request lands, and
            // retrieval says it again when the candidate searches actually begin - and the second
            // one is not news. Dropping it here rather than coordinating the call sites keeps
            // both of them honest about what they know.
            return;
        }
        turn.lastStage = stage;
        log.debug("turn +{}ms  {}", (System.nanoTime() - turn.startNanos) / 1_000_000, stage);
        try {
            turn.sink.report(stage);
        } catch (RuntimeException e) {
            log.debug("Could not report the stage \"{}\": {}", stage, e.getMessage());
        }
    }

    // The sink, the instant the turn opened - so every report can say how far in it is - and the
    // last stage announced, so an unchanged one is not announced twice.
    private static final class Turn {

        private final Sink sink;
        private final long startNanos;
        private String lastStage;

        private Turn(Sink sink, long startNanos) {
            this.sink = sink;
            this.startNanos = startNanos;
        }
    }

    @FunctionalInterface
    public interface Sink {

        void report(String stage);
    }

    // AutoCloseable minus the checked exception, so call sites are plain try-with-resources.
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
