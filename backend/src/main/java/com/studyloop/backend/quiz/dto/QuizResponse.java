package com.studyloop.backend.quiz.dto;

import com.studyloop.backend.quiz.QuestionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The take view of a quiz: every question with its prompt and (for multiple-choice) its choices,
// but WITHOUT the answer key or explanations — those are revealed only after an attempt is graded
// (Phase 7.2). For short-answer questions `options` is an empty list.
public record QuizResponse(
        UUID id,
        String title,
        Instant createdAt,
        List<QuizQuestionView> questions
) {

    public record QuizQuestionView(
            UUID id,
            int index,
            QuestionType type,
            String prompt,
            List<String> options
    ) {
    }
}
