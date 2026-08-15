package com.studyloop.backend.usage;

// Tells the recorder which feature is behind the provider call it is about to see.
//
// The problem it solves: ChatClient.completeJson() is one method with four callers — summaries,
// quiz generation, quiz grading, flashcards — and the client can't tell them apart. Threading an
// operation argument through ChatClient would put a bookkeeping parameter on an interface whose
// job is "text in, text out", and every future caller and test stub would inherit it.
//
// So the caller marks the thread instead, for the length of one call:
//
//     try (var ignored = AiUsageContext.of(AiOperation.QUIZ_GENERATION)) {
//         json = chatClient.completeJson(messages);
//     }
//
// Everything runs on the request thread (or, for streaming, one task on the chat executor), so a
// thread-local is exactly the right scope. Methods that already identify themselves — embed vs
// embedQuery, complete vs streamComplete — pass their own fallback and need no scope at all.
public final class AiUsageContext {

    private static final ThreadLocal<AiOperation> CURRENT = new ThreadLocal<>();

    private AiUsageContext() {
    }

    // Labels this thread until the returned scope is closed, restoring whatever was set before —
    // so a nested scope can't leak out and mislabel its caller's later calls.
    public static Scope of(AiOperation operation) {
        AiOperation previous = CURRENT.get();
        CURRENT.set(operation);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    // The label in force, or the caller's own guess when no scope is open.
    static AiOperation current(AiOperation fallback) {
        AiOperation operation = CURRENT.get();
        return operation != null ? operation : fallback;
    }

    // AutoCloseable, minus the checked exception, so call sites are plain try-with-resources.
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
