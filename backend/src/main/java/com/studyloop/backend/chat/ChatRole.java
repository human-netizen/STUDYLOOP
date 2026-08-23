package com.studyloop.backend.chat;

// Who authored a stored chat turn, and — since Phase 20.2 — on what authority. SYSTEM prompts are
// assembled per-request and never persisted, so no role exists for them.
//
// GENERAL is the third value and it is not cosmetic. An answer given from general knowledge is
// still the assistant speaking, but it is the one turn in a transcript that was not grounded in
// the course's materials, and two different readers need to be told: the student, who is shown it
// differently, and the model, which replays this transcript as context for the next question. A
// past turn indistinguishable from a grounded one is a past turn a later answer will cite [1] for.
public enum ChatRole {
    USER,
    ASSISTANT,
    GENERAL
}
