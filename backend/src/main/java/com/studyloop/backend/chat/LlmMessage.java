package com.studyloop.backend.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// One message sent to the chat model. `role` uses the provider's wire values ("system",
// "user", "assistant", "tool"); this is the neutral shape the ChatClient accepts, independent of
// how turns are stored (ChatRole) or retrieved.
//
// Phase 26.3 added the three tool fields, and they are nullable for a reason worth stating: the
// three factories below are unchanged, so **every caller written before this phase compiles
// untouched** — the quiz generator, the flashcard generator, the grader, the forum answerer, the
// video script and scene planners and the query expander all keep building two-field messages and
// none of them has learned what a tool is. `@JsonInclude(NON_NULL)` is what makes that true on the
// wire as well: a message with no tool fields serializes to exactly the two keys it always did.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmMessage(
        String role,
        String content,
        // On an assistant message: the calls the model asked for. Content is null when this is set
        // — the model either spoke or asked for a tool, never both in one message.
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        // On a `tool` message: which call this is the answer to.
        @JsonProperty("tool_call_id") String toolCallId,
        // The model's own prose about why it is calling the tool. **Carried, never shown.** It is
        // reasoning about what to search for, and putting it on screen would give a student an
        // uncited paragraph that reads like an answer. It rides along only because Cohere's own
        // examples send the assistant message back whole, and a field the provider wrote is the
        // safest thing to hand back to it.
        @JsonProperty("tool_plan") String toolPlan
) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, null, null, null);
    }

    // The assistant turn that asked for a tool, replayed back to the provider so the answer it
    // generates next knows what it requested.
    public static LlmMessage toolRequest(List<ToolCall> toolCalls, String toolPlan) {
        return new LlmMessage("assistant", null, toolCalls, null, toolPlan);
    }

    // What the tool returned, addressed to the call that asked for it.
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId, null);
    }
}
