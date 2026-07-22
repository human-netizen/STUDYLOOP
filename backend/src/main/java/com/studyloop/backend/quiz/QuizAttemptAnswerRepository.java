package com.studyloop.backend.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuizAttemptAnswerRepository extends JpaRepository<QuizAttemptAnswer, UUID> {
}
