package com.studyloop.backend.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

// One call the model asked for, in the provider's wire shape (Phase 26.3).
//
// `arguments` is a JSON *string*, not a parsed object, because that is what the provider streams:
// the argument object arrives in fragments across several `tool-call-delta` events and is only a
// document once the last one has landed. Keeping it a string here means this record can be built
// while it is still arriving and handed back to the provider verbatim afterwards.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(String id, String type, Function function) {

    public static ToolCall function(String id, String name, String arguments) {
        return new ToolCall(id, "function", new Function(name, arguments));
    }

    public String name() {
        return function == null ? null : function.name();
    }

    public String arguments() {
        return function == null ? null : function.arguments();
    }

    public record Function(String name, String arguments) { }
}
