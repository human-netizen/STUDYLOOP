package com.studyloop.backend.course;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    // Join-fetches the course and its owner so the caller can build a response without
    // extra lazy loads (open-in-view is off). Both are single-valued, so paginating in
    // SQL still works — the custom count query keeps totals correct.
    //
    // Phase 27.4 — archived courses are excluded unless asked for. The filter is in SQL rather
    // than applied to the page afterwards, because a page of twenty filtered down to fourteen in
    // memory is a page whose `totalElements` lies and whose second page skips rows.
    @Query(value = """
            select m from Membership m
            join fetch m.courseSpace cs
            join fetch cs.owner
            where m.user.id = :userId
              and (:includeArchived = true or cs.archivedAt is null)
            """,
            countQuery = """
                    select count(m) from Membership m
                    where m.user.id = :userId
                      and (:includeArchived = true or m.courseSpace.archivedAt is null)
                    """)
    Page<Membership> findByUserIdWithCourse(UUID userId, boolean includeArchived, Pageable pageable);

    @Query("""
            select m from Membership m
            join fetch m.courseSpace cs
            join fetch cs.owner
            where cs.id = :courseId and m.user.id = :userId
            """)
    Optional<Membership> findByCourseIdAndUserId(UUID courseId, UUID userId);

    // Phase 27.4 — the people in a course, so a manager has something to remove somebody from.
    // Join-fetches the user because every row renders their name and email, and twelve lazy loads
    // for a twelve-person course is the N+1 this repository already avoids everywhere else.
    @Query("""
            select m from Membership m
            join fetch m.user
            where m.courseSpace.id = :courseId
            order by m.role, m.createdAt
            """)
    List<Membership> findByCourseId(UUID courseId);

    // **The check behind "the last owner cannot leave".** A course with no OWNER is
    // unadministrable — nobody can rename it, upload to it, remove anybody from it or delete it —
    // and no cascade or constraint in this schema would notice it had happened. So it is a count,
    // read inside the same transaction as the delete.
    int countByCourseSpaceIdAndRole(UUID courseSpaceId, MembershipRole role);

    // Every course this user belongs to, for the account-deletion guard: it has to name the
    // courses that would be orphaned rather than just refusing.
    @Query("""
            select m from Membership m
            join fetch m.courseSpace cs
            where m.user.id = :userId
            """)
    List<Membership> findByUserId(UUID userId);
}
