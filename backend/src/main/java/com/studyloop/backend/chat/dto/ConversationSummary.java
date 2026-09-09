package com.studyloop.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

// One row of the caller's thread list (Phase 28.1).
//
// `title` is the first question, truncated — not a generated one. Naming a thread with a model
// call is the kind of cost that looks trivial in isolation and is then paid on the first turn of
// every conversation forever, and what a person scanning this list is looking for is the question
// they asked, which is free and already stored.
//
// `messageCount` counts every stored turn, the user's own included, because that is what "how long
// is this thread" means to the person reading the list. `updatedAt` is the conversation's own
// timestamp, which Hibernate stamps on each turn, so the list orders by recency without touching
// the messages table for the ordering.
public record ConversationSummary(
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        long messageCount
) {
}
