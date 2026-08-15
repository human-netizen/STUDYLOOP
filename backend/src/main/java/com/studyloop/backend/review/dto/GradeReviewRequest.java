package com.studyloop.backend.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// How well the card was recalled, on SM-2's 0-5 scale:
//   0 blackout · 1 wrong, answer felt familiar · 2 wrong, answer easy to recall once seen
//   3 correct but hard · 4 correct after hesitation · 5 instant
// Anything below 3 is a lapse and restarts the card.
public record GradeReviewRequest(
        @NotNull(message = "grade is required")
        @Min(value = 0, message = "grade must be between 0 and 5")
        @Max(value = 5, message = "grade must be between 0 and 5")
        Integer grade
) {
}
