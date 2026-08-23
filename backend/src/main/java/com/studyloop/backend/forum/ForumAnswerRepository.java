package com.studyloop.backend.forum;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ForumAnswerRepository extends JpaRepository<ForumAnswer, UUID> {

    // **A left join, since Phase 20.1.** It was an inner one, and an inner fetch join against a
    // column that is now nullable does not fail — it silently returns fewer rows. The assistant's
    // reply has no `createdBy`, so the thread would have rendered without the answer this whole
    // phase exists to post, with nothing throwing anywhere.
    @Query("""
            select a from ForumAnswer a
            left join fetch a.createdBy
            where a.thread.id = :threadId
            order by a.createdAt
            """)
    List<ForumAnswer> findByThreadWithAuthors(@Param("threadId") UUID threadId);

    // Scoped to the thread, so an answer id from another thread reads as 404 — accepting one is a
    // corpus write, and it must not be possible to aim it at a reply from somewhere else.
    Optional<ForumAnswer> findByIdAndThreadId(UUID id, UUID threadId);

    // Reply counts for the list view, as one query for the whole page rather than one per row.
    // Threads with no answers are simply absent from the result; the caller reads them as zero.
    @Query("""
            select a.thread.id as threadId, count(a) as total
            from ForumAnswer a
            where a.thread.id in :threadIds
            group by a.thread.id
            """)
    List<ThreadAnswerCount> countByThreadIds(@Param("threadIds") Collection<UUID> threadIds);

    // Which of these threads the assistant has already replied to (20.1). One query for the page,
    // like the counts above; the unique index means the answer is a set membership rather than a
    // count. The list view uses it to say "the assistant answered this" without opening it.
    @Query("""
            select distinct a.thread.id
            from ForumAnswer a
            where a.thread.id in :threadIds and a.authorKind = com.studyloop.backend.forum.ForumAnswerAuthor.ASSISTANT
            """)
    List<UUID> threadsAnsweredByAssistant(@Param("threadIds") Collection<UUID> threadIds);

    // The guard behind the one-machine-reply-per-thread rule, checked before a provider call
    // rather than caught afterwards as a constraint violation: the index is what makes the rule
    // true, and this is what stops the sweep paying for an answer it would then have to discard.
    boolean existsByThreadIdAndAuthorKind(UUID threadId, ForumAnswerAuthor authorKind);

    interface ThreadAnswerCount {
        UUID getThreadId();

        long getTotal();
    }
}
