package com.studyloop.backend.chat;

// Runs one tool the model asked for (Phase 26.3).
//
// It takes the call rather than a parsed argument object because the client has no business
// knowing what a tool's arguments mean — it carries a JSON string from the provider to whoever
// defined the tool, and the definer parses it. Which is also why the invoker, not the client, is
// where a malformed argument is handled: a model that sends `{"quer": "..."}` should get a tool
// result saying so, not an exception that loses the turn.
@FunctionalInterface
public interface ToolInvoker {

    ToolResult invoke(ToolCall call);
}
