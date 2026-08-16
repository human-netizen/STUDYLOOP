package com.studyloop.backend.forum;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ForumAnswerRepository extends JpaRepository<ForumAnswer, UUID> {

    @Query("""
            select a from ForumAnswer a
            join fetch a.createdBy
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

    interface ThreadAnswerCount {
        UUID getThreadId();

        long getTotal();
    }
}
