package com.studyloop.backend.chat;

// What running a tool produced (Phase 26.3). One of two things, and the second is the interesting
// one.
//
// `content` is the ordinary case: text to hand back to the model as a `tool` message, after which
// the conversation is re-sent and the model writes its answer from it.
//
// `finalAnswer` **ends the turn without a second provider call**. Two things inside the search tool
// can settle a question outright — the semantic cache already holds an answer to it, or the
// confidence gate refuses it — and in both cases the text that should reach the student exists
// already. Generating over it would be paying a model to paraphrase something the system has
// decided. This is the same short circuit `PreparedTurn.isAnswered` performs on the non-tool path,
// moved inside the tool because that is where the decision now happens.
public record ToolResult(String content, String finalAnswer) {

    public static ToolResult of(String content) {
        return new ToolResult(content, null);
    }

    public static ToolResult settled(String finalAnswer) {
        return new ToolResult(null, finalAnswer);
    }

    public boolean isSettled() {
        return finalAnswer != null;
    }
}
