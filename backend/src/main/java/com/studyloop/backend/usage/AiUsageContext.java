package com.studyloop.backend.usage;

import java.util.UUID;

// Tells the recorder two things about the provider call it is about to see: which feature asked
// for it, and whose allowance it comes out of.
//
// The problem it solves: ChatClient.completeJson() is one method with four callers — summaries,
// quiz generation, quiz grading, flashcards — and the client can't tell them apart. Threading an
// operation argument through ChatClient would put a bookkeeping parameter on an interface whose
// job is "text in, text out", and every future caller and test stub would inherit it. The actor
// is worse still: EmbeddingClient has no business knowing who logged in.
//
// So the caller marks the thread instead, for the length of one call:
//
//     try (var ignored = AiUsageContext.of(AiOperation.QUIZ_GENERATION)) {
//         json = chatClient.completeJson(messages);
//     }
//
// Everything runs on the request thread (or, for streaming and ingestion, one task on a worker
// executor), so a thread-local is exactly the right scope. Methods that already identify
// themselves — embed vs embedQuery, complete vs streamComplete — pass their own fallback and need
// no operation scope at all.
//
// The two axes are set independently because they are set in different places: the actor is
// established once per request (AiUsageAttributionFilter) or once per background task, while the
// operation changes several times inside one request. of(operation) therefore leaves the actor
// alone rather than clearing it — nesting an operation scope inside a request must not make the
// call anonymous.
public final class AiUsageContext {

    private static final ThreadLocal<Attribution> CURRENT = new ThreadLocal<>();

    private AiUsageContext() {
    }

    // Labels this thread's calls with a feature, keeping whatever actor is already in force.
    public static Scope of(AiOperation operation) {
        return set(new Attribution(operation, currentActor()));
    }

    // Names the person whose allowance the thread's calls come out of, keeping the feature label.
    public static Scope actor(UUID userId) {
        Attribution previous = CURRENT.get();
        return set(new Attribution(previous == null ? null : previous.operation(), userId));
    }

    // Both at once — for a background task that starts with neither, such as the SSE stream
    // worker or the ingestion executor, where there is no request thread to inherit from.
    public static Scope of(AiOperation operation, UUID userId) {
        return set(new Attribution(operation, userId));
    }

    private static Scope set(Attribution next) {
        Attribution previous = CURRENT.get();
        CURRENT.set(next);
        // Restores whatever was set before rather than clearing, so a nested scope can't leak out
        // and mislabel its caller's later calls.
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    // The feature label in force, or the caller's own guess when no scope is open.
    static AiOperation current(AiOperation fallback) {
        Attribution attribution = CURRENT.get();
        if (attribution == null || attribution.operation() == null) {
            return fallback;
        }
        return attribution.operation();
    }

    // Whose allowance this call belongs to, or null when nobody's — a scheduled sweep, a backfill,
    // or a call made before the request was authenticated. The ledger stores the null as-is.
    public static UUID currentActor() {
        Attribution attribution = CURRENT.get();
        return attribution == null ? null : attribution.userId();
    }

    private record Attribution(AiOperation operation, UUID userId) { }

    // AutoCloseable, minus the checked exception, so call sites are plain try-with-resources.
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
