package com.studyloop.backend.chat;

// Who authored a stored chat turn. SYSTEM prompts are assembled per-request and never
// persisted, so only the two conversational roles live in the database.
public enum ChatRole {
    USER,
    ASSISTANT
}
