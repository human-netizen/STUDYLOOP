package com.studyloop.backend.chat;

import java.util.List;

// Turns a sequence of chat messages into the model's reply. Abstracted from the provider so
// the service can gate on isConfigured() (no key → a graceful "chat unavailable" instead of a
// crash) and so tests can swap in a canned client without calling a real API.
public interface ChatClient {

    // Whether a provider is actually configured (e.g. an API key is present).
    boolean isConfigured();

    // Sends the messages (system + prior turns + the new question) and returns the reply text.
    String complete(List<LlmMessage> messages);
}
