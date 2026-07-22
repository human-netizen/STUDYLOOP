package com.studyloop.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    // A user's own attempts on a quiz, most recent first (attempt history).
    List<QuizAttempt> findByQuizIdAndUserIdOrderByCreatedAtDesc(UUID quizId, UUID userId);
}
