package com.studyloop.backend.forum;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ForumThreadRepository extends JpaRepository<ForumThread, UUID> {

    // The list view needs each thread's author name, and createdBy is lazy — without the fetch
    // join this is one extra SELECT per row.
    @Query("""
            select t from ForumThread t
            join fetch t.createdBy
            where t.courseSpace.id = :courseId
            order by t.createdAt desc
            """)
    List<ForumThread> findByCourseWithAuthors(@Param("courseId") UUID courseId);

    @Query("""
            select t from ForumThread t
            join fetch t.createdBy
            where t.courseSpace.id = :courseId and t.status = :status
            order by t.createdAt desc
            """)
    List<ForumThread> findByCourseAndStatusWithAuthors(@Param("courseId") UUID courseId,
                                                       @Param("status") ForumThreadStatus status);

    // Scoped to the course, so a thread id from another course reads as 404.
    Optional<ForumThread> findByIdAndCourseSpaceId(UUID id, UUID courseSpaceId);

    // Escalating the same refusal twice is the same thread. A partial unique index enforces it
    // against the race; this makes the common case a friendly no-op instead of a 409.
    Optional<ForumThread> findByCourseSpaceIdAndQuestionEventId(UUID courseSpaceId, UUID questionEventId);
}
