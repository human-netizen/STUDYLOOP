package com.studyloop.backend.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourseInviteRepository extends JpaRepository<CourseInvite, UUID> {

    // Redeeming/previewing needs the course and its owner, so join-fetch them (open-in-view
    // is off) to build the response without a lazy hop after the transaction closes.
    @Query("""
            select i from CourseInvite i
            join fetch i.courseSpace cs
            join fetch cs.owner
            where i.token = :token
            """)
    Optional<CourseInvite> findByTokenWithCourse(String token);

    // Active (not revoked, and thus not-yet-spent) invites for a course, newest first.
    // The listing builds its response from the invite row plus the path's course id, so no
    // fetch of the course association is needed here.
    List<CourseInvite> findByCourseSpaceIdAndRevokedFalseOrderByCreatedAtDesc(UUID courseId);
}
