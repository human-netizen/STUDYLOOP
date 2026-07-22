package com.studyloop.backend.quiz.dto;

import java.time.Instant;
import java.util.UUID;

// A past attempt as it appears in the user's attempt history for a quiz.
public record AttemptSummaryResponse(
        UUID attemptId,
        int score,
        int total,
        Instant createdAt
) {
}
