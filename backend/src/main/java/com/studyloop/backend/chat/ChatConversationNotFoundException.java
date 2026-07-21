package com.studyloop.backend.chat;

import java.util.UUID;

// Raised when a conversation id doesn't resolve for the caller in the given course — whether
// it truly doesn't exist or belongs to another user/course (we don't distinguish, to avoid
// leaking existence).
public class ChatConversationNotFoundException extends RuntimeException {

    public ChatConversationNotFoundException(UUID id) {
        super("Conversation not found: " + id);
    }
}
