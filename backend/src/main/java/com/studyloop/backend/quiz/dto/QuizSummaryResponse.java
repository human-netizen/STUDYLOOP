package com.studyloop.backend.quiz.dto;

import java.time.Instant;
import java.util.UUID;

// A quiz as it appears in the course's quiz list — just enough to render a row and link to it.
public record QuizSummaryResponse(
        UUID id,
        String title,
        int questionCount,
        Instant createdAt
) {
}
