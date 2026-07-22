package com.studyloop.backend.quiz;

import java.util.UUID;

// A quiz id that doesn't exist in the given course → 404 (see GlobalExceptionHandler).
public class QuizNotFoundException extends RuntimeException {

    public QuizNotFoundException(UUID quizId) {
        super("Quiz not found: " + quizId);
    }
}
