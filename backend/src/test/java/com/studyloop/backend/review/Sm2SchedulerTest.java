package com.studyloop.backend.review;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// The SM-2 rules on their own — no Spring, no database, no waiting for real days to pass. Every
// case here is the algorithm's published behaviour, so if a refactor changes an interval this
// test says exactly which rule broke.
class Sm2SchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    private ReviewState newCard() {
        ReviewState state = new ReviewState();
        state.setDueOn(TODAY);
        return state;
    }

    // Walks a card forward by grading it repeatedly, returning the state after the last grade.
    private ReviewState after(ReviewState state, LocalDate date, int... grades) {
        for (int grade : grades) {
            Sm2Scheduler.apply(state, grade, date).applyTo(state, date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        }
        return state;
    }

    @Test
    void firstSuccessfulReviewComesBackTomorrow() {
        ReviewState state = after(newCard(), TODAY, 5);

        assertThat(state.getRepetitions()).isEqualTo(1);
        assertThat(state.getIntervalDays()).isEqualTo(1);
        assertThat(state.getDueOn()).isEqualTo(TODAY.plusDays(1));
        // A perfect grade adds 0.1 to the ease factor.
        assertThat(state.getEaseFactor()).isCloseTo(2.6, within(1e-9));
    }

    @Test
    void secondSuccessfulReviewJumpsToSixDays() {
        ReviewState state = after(newCard(), TODAY, 5, 5);

        assertThat(state.getRepetitions()).isEqualTo(2);
        assertThat(state.getIntervalDays()).isEqualTo(6);
        assertThat(state.getDueOn()).isEqualTo(TODAY.plusDays(6));
    }

    // From the third success on, the interval is the previous one multiplied by the ease factor.
    @Test
    void laterIntervalsMultiplyByTheEaseFactor() {
        ReviewState state = after(newCard(), TODAY, 5, 5, 5);

        // EF after three 5s: 2.5 → 2.6 → 2.7 → 2.8; interval 6 * 2.8 = 16.8, rounded to 17.
        assertThat(state.getEaseFactor()).isCloseTo(2.8, within(1e-9));
        assertThat(state.getIntervalDays()).isEqualTo(17);
        assertThat(state.getDueOn()).isEqualTo(TODAY.plusDays(17));
    }

    @Test
    void gradeOfFourLeavesTheEaseFactorAlone() {
        ReviewState state = after(newCard(), TODAY, 4);

        assertThat(state.getEaseFactor()).isCloseTo(Sm2Scheduler.INITIAL_EASE_FACTOR, within(1e-9));
    }

    // A bare pass still costs ease, so a card you keep barely remembering comes back more often.
    @Test
    void gradeOfThreeLowersTheEaseFactor() {
        ReviewState state = after(newCard(), TODAY, 3);

        assertThat(state.getEaseFactor()).isCloseTo(2.36, within(1e-9));
        assertThat(state.getRepetitions()).isEqualTo(1);
    }

    @Test
    void failingRestartsTheCardTomorrowAndCountsALapse() {
        ReviewState state = after(newCard(), TODAY, 5, 5, 5);
        double easeBeforeTheLapse = state.getEaseFactor();

        after(state, TODAY.plusDays(17), 1);

        assertThat(state.getRepetitions()).isZero();
        assertThat(state.getIntervalDays()).isEqualTo(1);
        assertThat(state.getLapses()).isEqualTo(1);
        assertThat(state.getDueOn()).isEqualTo(TODAY.plusDays(18));
        // The original algorithm restarts the repetitions WITHOUT touching the ease factor: one
        // bad day shouldn't permanently mark the card as hard.
        assertThat(state.getEaseFactor()).isCloseTo(easeBeforeTheLapse, within(1e-9));
    }

    @Test
    void easeFactorNeverFallsBelowTheFloor() {
        ReviewState state = newCard();
        // Ten bare passes drive EF down by 0.14 each time — well past 1.3 if it weren't clamped.
        after(state, TODAY, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3);

        assertThat(state.getEaseFactor()).isEqualTo(Sm2Scheduler.MINIMUM_EASE_FACTOR);
    }

    // At the floor the interval still has to grow, not stall: 6 * 1.3 = 7.8 → 8.
    @Test
    void intervalStillGrowsAtTheEaseFloor() {
        ReviewState state = new ReviewState();
        state.setDueOn(TODAY);
        state.setEaseFactor(Sm2Scheduler.MINIMUM_EASE_FACTOR);
        state.setIntervalDays(6);
        state.setRepetitions(2);

        after(state, TODAY, 4);

        assertThat(state.getIntervalDays()).isEqualTo(8);
    }

    @Test
    void gradesOutsideZeroToFiveAreRejected() {
        assertThatThrownBy(() -> Sm2Scheduler.apply(newCard(), 6, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Scheduler.apply(newCard(), -1, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyDoesNotMutateTheStateItIsGiven() {
        ReviewState state = newCard();

        Sm2Scheduler.apply(state, 5, TODAY);

        assertThat(state.getRepetitions()).isZero();
        assertThat(state.getIntervalDays()).isZero();
        assertThat(state.getDueOn()).isEqualTo(TODAY);
    }
}
