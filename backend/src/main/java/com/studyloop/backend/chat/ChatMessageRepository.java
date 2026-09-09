package com.studyloop.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Oldest-first, so replaying to the model preserves the turn order.
    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    // The transcript and the ownership check in one statement (Phase 26.2).
    //
    // Continuing a thread used to cost two round trips: one to load the conversation and prove the
    // caller owns it, another to read its messages. The predicate here is the one
    // `findByIdAndCourseSpaceIdAndCreatedById` uses, moved into the message query, so a thread
    // that is not yours returns nothing for the same reason it used to 404.
    //
    // **Written against the messages rather than as a join fetch of a mapped collection, and that
    // is not a style choice.** A collection on `ChatConversation` is served from the persistence
    // context whenever the conversation is already managed there, which means the second turn of a
    // thread inside one transaction gets the collection as it was when the entity was constructed
    // - empty - and the model is handed a thread with no history while every query against the
    // database sees the rows. It was written that way first and this test caught it. A query reads
    // what is there.
    @Query("""
            select m from ChatMessage m
            where m.conversation.id = :conversationId
              and m.conversation.courseSpace.id = :courseSpaceId
              and m.conversation.createdBy.id = :createdById
            order by m.createdAt asc
            """)
    List<ChatMessage> findOwnedHistory(@Param("conversationId") UUID conversationId,
                                       @Param("courseSpaceId") UUID courseSpaceId,
                                       @Param("createdById") UUID createdById);
}
