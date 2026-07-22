package com.studyloop.backend.quiz.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// Asks the server to generate a quiz from a course's materials. `documentIds` picks which
// documents to quiz on; null or empty means all of the course's READY documents. The two counts
// are how many of each question kind to produce (the service applies sane defaults when null and
// clamps the total). `title` is optional — the service derives one when omitted.
public record GenerateQuizRequest(
        List<UUID> documentIds,

        @Min(value = 0, message = "Count cannot be negative.")
        @Max(value = 20, message = "At most 20 multiple-choice questions.")
        Integer multipleChoiceCount,

        @Min(value = 0, message = "Count cannot be negative.")
        @Max(value = 20, message = "At most 20 short-answer questions.")
        Integer shortAnswerCount,

        @Size(max = 200, message = "Title is too long.")
        String title
) {
}
