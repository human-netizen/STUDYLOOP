package com.studyloop.backend.quiz.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

// A quiz submission: one answer per question the user responded to. Questions left out (or with
// both fields null) are graded as incorrect. For a multiple-choice question set
// `selectedOptionIndex`; for a short-answer question set `answerText`.
public record SubmitAttemptRequest(
        @NotNull(message = "Answers must not be null.")
        List<AnswerInput> answers
) {

    public record AnswerInput(
            @NotNull(message = "questionId is required.")
            UUID questionId,
            Integer selectedOptionIndex,
            String answerText
    ) {
    }
}
