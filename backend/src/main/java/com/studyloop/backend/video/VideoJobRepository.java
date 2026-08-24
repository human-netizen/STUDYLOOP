package com.studyloop.backend.video;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoJobRepository extends JpaRepository<VideoJob, UUID> {

    // A member's own jobs in a course. Deliberately not "the course's jobs": a video can be
    // grounded on the requester's private notes (21.2), so the list is per-person for the same
    // reason the file is.
    List<VideoJob> findByCourseSpaceIdAndRequestedByIdOrderByCreatedAtDesc(UUID courseId, UUID requesterId);

    // Scoped to both the course and the requester in one query rather than fetched and then
    // checked, so the 404 for "somebody else's job" and the 404 for "no such job" are the same
    // code path and cannot drift apart into an existence leak.
    Optional<VideoJob> findByIdAndCourseSpaceIdAndRequestedById(UUID id, UUID courseId, UUID requesterId);

    // The per-user daily cap (21.1). Counted across all courses, not per course: the cost being
    // capped is the model's and the machine's, and neither of them cares which course the render
    // was for.
    @Query("""
            select count(j) from VideoJob j
            where j.requestedBy.id = :requesterId
              and j.createdAt >= :since
              and j.status <> com.studyloop.backend.video.VideoJobStatus.REFUSED
            """)
    long countRecentByRequester(@Param("requesterId") UUID requesterId, @Param("since") Instant since);

    // What the startup sweep reads: everything a dead process left mid-flight. QUEUED is in the
    // list because an in-process queue loses its backlog too — those jobs were accepted by a
    // runtime that no longer exists, and nothing is going to pick them up.
    @Query("""
            select j from VideoJob j
            where j.status in (com.studyloop.backend.video.VideoJobStatus.QUEUED,
                               com.studyloop.backend.video.VideoJobStatus.PLANNING,
                               com.studyloop.backend.video.VideoJobStatus.RENDERING,
                               com.studyloop.backend.video.VideoJobStatus.COMPOSING)
            """)
    List<VideoJob> findUnfinished();

    // How many renders are in flight right now, read by the admission check that keeps
    // max-concurrent honest across the whole process rather than per request thread.
    @Query("""
            select count(j) from VideoJob j
            where j.status in (com.studyloop.backend.video.VideoJobStatus.PLANNING,
                               com.studyloop.backend.video.VideoJobStatus.RENDERING,
                               com.studyloop.backend.video.VideoJobStatus.COMPOSING)
            """)
    long countInFlight();
}
