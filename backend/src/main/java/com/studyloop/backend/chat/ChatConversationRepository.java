package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    // Loads a conversation only when it belongs to the given course AND was started by the
    // caller — so continuing a thread can't reach across courses or into someone else's chat.
    Optional<ChatConversation> findByIdAndCourseSpaceIdAndCreatedById(
            UUID id, UUID courseSpaceId, UUID createdById);

    // Phase 28.1 — the caller's own threads in one course, newest first.
    //
    // **The predicate is the same one `findByIdAndCourseSpaceIdAndCreatedById` uses, minus the
    // id**, which is what makes the list impossible to widen by accident: a thread appears here for
    // exactly the reason it can be continued, and a classmate's thread is absent for exactly the
    // reason continuing it 404s. 26.2 made that argument for the transcript query; this is the same
    // argument one predicate shorter.
    //
    // The count is a left join rather than a correlated subquery so the whole list is one pass over
    // `idx_chat_messages_conversation`, and it is a *left* join because a conversation with no
    // messages is a real state — `prepare` creates the row before the first turn is saved, and a
    // provider failure between the two leaves it empty. Dropping it from the list would hide a
    // thread the delete endpoint is the only way to be rid of.
    @Query("""
            select new com.studyloop.backend.chat.dto.ConversationSummary(
                c.id, c.title, c.createdAt, c.updatedAt, count(m.id))
            from ChatConversation c
            left join ChatMessage m on m.conversation.id = c.id
            where c.courseSpace.id = :courseSpaceId
              and c.createdBy.id = :createdById
            group by c.id, c.title, c.createdAt, c.updatedAt
            order by c.updatedAt desc
            """)
    List<ConversationSummary> findOwnedSummaries(@Param("courseSpaceId") UUID courseSpaceId,
                                                 @Param("createdById") UUID createdById);
}
