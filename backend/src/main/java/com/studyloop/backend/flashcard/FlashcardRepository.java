package com.studyloop.backend.flashcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlashcardRepository extends JpaRepository<Flashcard, UUID> {

    // The caller's own cards in a course, most recent first.
    List<Flashcard> findByCourseSpaceIdAndCreatedByIdOrderByCreatedAtDesc(UUID courseSpaceId, UUID createdById);

    // Ownership-scoped lookup: a card is only reachable by its owner within its course, so another
    // user's or another course's id reads as absent (404 on delete).
    Optional<Flashcard> findByIdAndCourseSpaceIdAndCreatedById(UUID id, UUID courseSpaceId, UUID createdById);

    // Which of these quiz questions already have a card for this user — the idempotency check
    // behind auto-enrolling missed questions, done in one query instead of one per question.
    List<Flashcard> findByCreatedByIdAndSourceQuizQuestionIdIn(UUID createdById, Collection<UUID> questionIds);
}
