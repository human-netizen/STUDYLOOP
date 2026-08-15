package com.studyloop.backend.review.dto;

import com.studyloop.backend.review.ReviewState;

import java.time.LocalDate;
import java.util.UUID;

// What grading a card did to its schedule, plus how many cards are still waiting today — enough
// for the UI to say "next in 6 days · 4 left" without a second request.
public record ReviewResultResponse(
        UUID cardId,
        int grade,
        boolean lapsed,
        LocalDate dueOn,
        int intervalDays,
        int repetitions,
        int lapses,
        double easeFactor,
        long remainingToday
) {

    public static ReviewResultResponse from(ReviewState state, int grade, boolean lapsed, long remainingToday) {
        return new ReviewResultResponse(
                state.getFlashcardId(),
                grade,
                lapsed,
                state.getDueOn(),
                state.getIntervalDays(),
                state.getRepetitions(),
                state.getLapses(),
                state.getEaseFactor(),
                remainingToday);
    }
}
