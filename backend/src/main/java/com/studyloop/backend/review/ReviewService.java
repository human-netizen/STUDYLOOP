package com.studyloop.backend.review;

import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.flashcard.Flashcard;
import com.studyloop.backend.review.dto.GradeReviewRequest;
import com.studyloop.backend.review.dto.ReviewCardResponse;
import com.studyloop.backend.review.dto.ReviewResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// The daily review queue. Cards are personal, so everything here is scoped to the caller: you can
// only see and grade your own cards, and a course filter still has to pass the membership check.
//
// Scheduling itself lives in Sm2Scheduler; this class owns the transaction, the ownership rules
// and the clock.
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewStateRepository reviewStateRepository;
    private final CourseAccess courseAccess;
    private final Clock clock;

    // Cards due on or before today, most overdue first. courseId is optional: null means every
    // course the caller belongs to.
    @Transactional(readOnly = true)
    public List<ReviewCardResponse> queue(UUID actorId, UUID courseId) {
        if (courseId != null) {
            courseAccess.requireMember(actorId, courseId);
        }
        return reviewStateRepository.findDue(actorId, courseId, today()).stream()
                .map(ReviewCardResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long dueCount(UUID actorId, UUID courseId) {
        if (courseId != null) {
            courseAccess.requireMember(actorId, courseId);
        }
        return reviewStateRepository.countDue(actorId, courseId, today());
    }

    // Grades a card and reschedules it. Grading a card that isn't due yet is allowed — studying
    // ahead is not an error — it simply pushes the next due date out from today.
    @Transactional
    public ReviewResultResponse grade(UUID actorId, UUID cardId, GradeReviewRequest request) {
        ReviewState state = reviewStateRepository.findOwnedBy(cardId, actorId)
                .orElseThrow(() -> new ReviewCardNotFoundException(cardId));

        int grade = request.grade();
        LocalDate today = today();
        boolean lapsed = grade < 3;

        Sm2Scheduler.apply(state, grade, today).applyTo(state, Instant.now(clock));
        reviewStateRepository.flush();

        // Counted after the update, so the card just graded is no longer part of "remaining".
        long remaining = reviewStateRepository.countDue(actorId, null, today);
        return ReviewResultResponse.from(state, grade, lapsed, remaining);
    }

    // Called when a flashcard is created, so every card enters the queue the moment it exists.
    // Returns the saved state; callers inside an existing transaction need not flush.
    @Transactional
    public ReviewState enroll(Flashcard card) {
        return reviewStateRepository.save(ReviewState.freshFor(card, today()));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
