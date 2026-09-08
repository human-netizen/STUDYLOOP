package com.studyloop.backend.document;

// Where an ingest has got to, reported by whichever step is running (Phase 25.2).
//
// **A thread-scoped sink rather than a parameter, which is the same trade AiUsageContext already
// made here and for the same reason.** The two steps with something worth reporting are the vision
// loop and the embedding pass, and neither can be handed a document id without widening an
// interface whose narrowness is the point: `DocumentExtractor` is "bytes in, Markdown pages out"
// — that contract is what let three formats be added in Phase 16 without the orchestrator learning
// what a slide is — and `EmbeddingClient` has no business knowing that documents exist. Threading
// a progress callback through both would put a bookkeeping parameter on every implementation, every
// test stub and every future format.
//
// Ingestion runs as exactly one task on one worker thread (`ingestionExecutor`, started by
// DocumentIngestionListener), so a thread-local is not a compromise here — it is precisely the
// scope of one ingest.
//
// **Unscoped is a no-op, and that property is load-bearing.** The eval harness, the fixture corpus
// report and every extraction test call these components directly with no scope open; they report
// into nothing and behave exactly as they did before this class existed.
public final class IngestionProgress {

    // Where each status begins. The bar and the status badge are rendered from the same row, so a
    // step that reports nothing still has to leave the bar somewhere consistent with the badge
    // beside it — markStatus moves it to the floor of the band it is entering, and the step moves
    // it within the band from there.
    //
    // The bands are sized by how long each step takes rather than by how many steps there are:
    // extraction owns half the bar because on the documents this phase was written for it is
    // essentially all of the wall clock, and chunking owns fifteen points for work that finishes
    // in seconds so that the bar visibly moves rather than teleports.
    public static final int EXTRACTING_FLOOR = 5;
    public static final int EXTRACTING_CEILING = 55;
    public static final int CHUNKING_FLOOR = 55;
    public static final int EMBEDDING_FLOOR = 70;
    public static final int EMBEDDING_CEILING = 98;
    public static final int DONE = 100;

    private static final ThreadLocal<Sink> CURRENT = new ThreadLocal<>();

    private IngestionProgress() {
    }

    // Directs this thread's reports at a sink for the length of one ingest. Restores whatever was
    // in force rather than clearing, so a nested scope cannot silence its caller's reporting.
    public static Scope to(Sink sink) {
        Sink previous = CURRENT.get();
        CURRENT.set(sink);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    // Reports where the running step has got to. Silently does nothing when no scope is open.
    public static void report(int percent, String stage) {
        Sink sink = CURRENT.get();
        if (sink != null) {
            sink.report(Math.clamp(percent, 0, DONE), stage);
        }
    }

    // A step that is `done` of `total` through a band reports the point that fraction across it.
    // Guards `total <= 0` because "0 of 0 pages" is a real case — a document that routed nothing —
    // and a division here would fail an ingest to draw a bar.
    public static void report(int floor, int ceiling, int done, int total, String stage) {
        int percent = total <= 0 ? floor : floor + (ceiling - floor) * done / total;
        report(percent, stage);
    }

    // Where the bar sits when a status is entered and nothing has reported yet.
    public static int floorOf(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> 0;
            case EXTRACTING -> EXTRACTING_FLOOR;
            case CHUNKING -> CHUNKING_FLOOR;
            case EMBEDDING -> EMBEDDING_FLOOR;
            case READY -> DONE;
            // Never used: a failure holds the percentage it reached, because where it stopped is
            // the most useful thing a failed row can say. Present so the switch is exhaustive
            // without a default that would swallow a status added later.
            case FAILED -> 0;
        };
    }

    // The sentence shown under the bar when a step has not written its own.
    public static String stageOf(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> "Waiting to start.";
            case EXTRACTING -> "Reading the file.";
            case CHUNKING -> "Splitting it into passages.";
            case EMBEDDING -> "Indexing the passages for search.";
            case READY -> "Ready to answer questions.";
            case FAILED -> null;
        };
    }

    @FunctionalInterface
    public interface Sink {

        void report(int percent, String stage);
    }

    // AutoCloseable minus the checked exception, so call sites are plain try-with-resources.
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
