package com.studyloop.backend.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewStateRepository extends JpaRepository<ReviewState, UUID> {

    // Today's queue for one user: their own cards, due on or before today, most overdue first.
    // Open-in-view is off, so the card and its course are join-fetched here rather than lazily
    // touched while building the response. courseId is optional — pass null for every course.
    @Query("""
            select s from ReviewState s
              join fetch s.flashcard c
              join fetch c.courseSpace cs
            where c.createdBy.id = :userId
              and s.dueOn <= :today
              and (:courseId is null or cs.id = :courseId)
            order by s.dueOn asc, c.createdAt asc
            """)
    List<ReviewState> findDue(@Param("userId") UUID userId,
                              @Param("courseId") UUID courseId,
                              @Param("today") LocalDate today);

    // Ownership-scoped lookup for grading: another user's card reads as absent (→ 404).
    @Query("""
            select s from ReviewState s
              join fetch s.flashcard c
              join fetch c.courseSpace
            where s.flashcardId = :cardId
              and c.createdBy.id = :userId
            """)
    Optional<ReviewState> findOwnedBy(@Param("cardId") UUID cardId, @Param("userId") UUID userId);

    // How many of the caller's cards are waiting, without loading them.
    @Query("""
            select count(s) from ReviewState s
            where s.flashcard.createdBy.id = :userId
              and s.dueOn <= :today
              and (:courseId is null or s.flashcard.courseSpace.id = :courseId)
            """)
    long countDue(@Param("userId") UUID userId,
                  @Param("courseId") UUID courseId,
                  @Param("today") LocalDate today);
}
