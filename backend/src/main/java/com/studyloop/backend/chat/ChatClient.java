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
}
