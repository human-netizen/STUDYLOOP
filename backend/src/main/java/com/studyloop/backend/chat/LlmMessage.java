package com.studyloop.backend.chat;

// One message sent to the chat model. `role` uses the provider's wire values ("system",
// "user", "assistant"); this is the neutral shape the ChatClient accepts, independent of
// how turns are stored (ChatRole) or retrieved.
public record LlmMessage(String role, String content) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }
}
