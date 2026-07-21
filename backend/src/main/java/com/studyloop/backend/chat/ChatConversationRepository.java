package com.studyloop.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    // Loads a conversation only when it belongs to the given course AND was started by the
    // caller — so continuing a thread can't reach across courses or into someone else's chat.
    Optional<ChatConversation> findByIdAndCourseSpaceIdAndCreatedById(
            UUID id, UUID courseSpaceId, UUID createdById);
}
