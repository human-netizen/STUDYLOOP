package com.studyloop.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Oldest-first, so replaying to the model preserves the turn order.
    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
