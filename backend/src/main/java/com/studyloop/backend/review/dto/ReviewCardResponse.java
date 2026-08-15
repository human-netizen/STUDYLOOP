package com.studyloop.backend.review.dto;

import com.studyloop.backend.flashcard.Flashcard;
import com.studyloop.backend.review.ReviewState;

import java.time.LocalDate;
import java.util.UUID;

// One card in the review queue: the card itself plus enough of its schedule for the UI to show
// how overdue it is and how well it is known.
public record ReviewCardResponse(
        UUID cardId,
        UUID courseId,
        String courseName,
        String front,
        String back,
        UUID sourceDocumentId,
        Integer sourcePage,
        LocalDate dueOn,
        int intervalDays,
        int repetitions,
        int lapses,
        double easeFactor
) {

    public static ReviewCardResponse from(ReviewState state) {
        Flashcard card = state.getFlashcard();
        return new ReviewCardResponse(
                card.getId(),
                card.getCourseSpace().getId(),
                card.getCourseSpace().getName(),
                card.getFront(),
                card.getBack(),
                card.getSourceDocumentId(),
                card.getSourcePage(),
                state.getDueOn(),
                state.getIntervalDays(),
                state.getRepetitions(),
                state.getLapses(),
                state.getEaseFactor());
    }
}
