package com.studyloop.backend.chat;

import java.util.List;
import java.util.Map;

// A tool offered to the model, in the shape the provider's request expects (Phase 26.3).
//
// **The description is where the instruction has to live**, not the system prompt — that is the
// text the model reads at the moment it is deciding whether to search. ZenLearn marks its
// equivalent `PRIMARY: Search course content first`; the same instinct, spelled out, is the
// description on the one tool this product offers.
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    // A single required string parameter, which is every tool this product has and probably every
    // one it will have. Written as a helper so a call site describes the parameter rather than
    // building JSON Schema by hand.
    public static ToolSpec oneStringArgument(String name, String description, String argument,
                                             String argumentDescription) {
        return new ToolSpec(name, description, Map.of(
                "type", "object",
                "properties", Map.of(argument, Map.of(
                        "type", "string",
                        "description", argumentDescription)),
                "required", List.of(argument)));
    }
}
