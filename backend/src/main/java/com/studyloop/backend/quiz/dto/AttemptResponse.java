package com.studyloop.backend.quiz.dto;

import com.studyloop.backend.quiz.QuestionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The graded result of a submission. `score` of `total` is how many questions were correct; each
// entry in `answers` pairs what the user gave with the correct answer and the explanation, so the
// UI can render a full review. This is where the answer key is revealed (the take view hides it).
//
// `cardsEnrolled` is how many of the missed questions became new review cards (Phase 8.1) — zero
// on a perfect score, and also zero on a retake where the same questions already have cards.
public record AttemptResponse(
        UUID attemptId,
        UUID quizId,
        int score,
        int total,
        Instant createdAt,
        List<GradedAnswer> answers,
        int cardsEnrolled
) {

    public record GradedAnswer(
            UUID questionId,
            int index,
            QuestionType type,
            String prompt,
            List<String> options,
            // What the user gave (one is null depending on the question type).
            Integer selectedOptionIndex,
            String answerText,
            boolean correct,
            // The answer key: the correct option index for MC, the reference answer for short.
            Integer correctOptionIndex,
            String correctAnswer,
            String explanation
    ) {
    }
}
