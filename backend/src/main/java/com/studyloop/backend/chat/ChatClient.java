package com.studyloop.backend.chat;

import java.util.List;
import java.util.function.Consumer;

// Turns a sequence of chat messages into the model's reply. Abstracted from the provider so
// the service can gate on isConfigured() (no key → a graceful "chat unavailable" instead of a
// crash) and so tests can swap in a canned client without calling a real API.
public interface ChatClient {

    // Whether a provider is actually configured (e.g. an API key is present).
    boolean isConfigured();

    // Sends the messages (system + prior turns + the new question) and returns the reply text.
    String complete(List<LlmMessage> messages);

    // Structured-output variant: asks the provider to return a single JSON object (nothing else)
    // and returns that JSON as a raw string for the caller to parse. Used by features that need
    // machine-readable output — quiz questions, flashcards, short-answer grading — rather than
    // prose. The prompt must describe the exact JSON shape expected.
    String completeJson(List<LlmMessage> messages);

    // Streaming variant: sends the same messages but asks the provider to emit the answer
    // incrementally, invoking onDelta for each text fragment as it arrives. Returns the full
    // concatenated answer once the stream ends (so the caller can persist it in one piece).
    String streamComplete(List<LlmMessage> messages, Consumer<String> onDelta);

    // Phase 26.3 — the same stream, with one difference: the model may answer the question or ask
    // for a tool first, and it decides which.
    //
    // **One new method rather than three changed ones.** `complete`, `completeJson` and
    // `streamComplete` keep their signatures exactly, because the eight features behind them —
    // quizzes, flashcards, grading, summaries, the forum answerer, the video script and scene
    // planners, the query expander — must not learn what a tool is to keep compiling. Only the
    // chat stream calls this.
    //
    // The loop it runs is deliberately small and hard-capped: at most one round of tool calls,
    // after which the conversation is re-sent *without* the tools, so there is no loop to run away
    // and the cap is structural rather than a counter somebody has to maintain. `onDelta` receives
    // every fragment the student should see — whether the model answered straight away, answered
    // from a tool result, or the tool settled the question by itself — so the caller's contract is
    // the same one `streamComplete` has: what came through onDelta is what was returned.
    String streamWithTools(List<LlmMessage> messages, List<ToolSpec> tools, ToolInvoker invoker,
                           Consumer<String> onDelta);
}
