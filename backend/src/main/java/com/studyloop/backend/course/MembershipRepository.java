package com.studyloop.backend.course;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    // Join-fetches the course and its owner so the caller can build a response without
    // extra lazy loads (open-in-view is off). Both are single-valued, so paginating in
    // SQL still works — the custom count query keeps totals correct.
    @Query(value = """
            select m from Membership m
            join fetch m.courseSpace cs
            join fetch cs.owner
            where m.user.id = :userId
            """,
            countQuery = "select count(m) from Membership m where m.user.id = :userId")
    Page<Membership> findByUserIdWithCourse(UUID userId, Pageable pageable);

    @Query("""
            select m from Membership m
            join fetch m.courseSpace cs
            join fetch cs.owner
            where cs.id = :courseId and m.user.id = :userId
            """)
    Optional<Membership> findByCourseIdAndUserId(UUID courseId, UUID userId);
}
