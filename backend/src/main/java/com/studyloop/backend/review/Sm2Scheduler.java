package com.studyloop.backend.review;

import java.time.LocalDate;

// SM-2, the SuperMemo 2 algorithm, as a pure function: current state + grade + today in, next
// state out. Nothing here touches Spring, the database or the clock, so the scheduling rules can
// be tested exhaustively without a context — see Sm2SchedulerTest.
//
// The grade is the learner's self-assessment of the recall, 0-5:
//   0-2 = failed (blackout, wrong, wrong-but-familiar)   3-5 = recalled (hard, good, easy)
//
// The algorithm, from Wozniak's original description:
//   I(1) = 1 day, I(2) = 6 days, I(n) = round(I(n-1) * EF) for n > 2
//   EF' = EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02)), floored at 1.3
//   if q < 3: restart the repetitions from the beginning WITHOUT changing EF
//
// That last rule is the one implementations most often get wrong (many update EF on a failure
// too). Following the original keeps a bad day from permanently souring a card's ease: the
// punishment is the reset to a one-day interval, not a lower EF forever.
public final class Sm2Scheduler {

    public static final double INITIAL_EASE_FACTOR = 2.5;
    public static final double MINIMUM_EASE_FACTOR = 1.3;
    public static final int MIN_GRADE = 0;
    public static final int MAX_GRADE = 5;

    // Below this the answer counts as forgotten and the card restarts.
    private static final int PASS_GRADE = 3;
    private static final int FIRST_INTERVAL_DAYS = 1;
    private static final int SECOND_INTERVAL_DAYS = 6;

    private Sm2Scheduler() {
    }

    // The state a card should be in after being graded today. `current` is not mutated.
    public static Outcome apply(ReviewState current, int grade, LocalDate today) {
        if (grade < MIN_GRADE || grade > MAX_GRADE) {
            throw new IllegalArgumentException("Grade must be between 0 and 5, was " + grade);
        }

        if (grade < PASS_GRADE) {
            // Lapse: back to the start of the ladder, due again tomorrow, ease untouched.
            return new Outcome(current.getEaseFactor(), FIRST_INTERVAL_DAYS, 0,
                    current.getLapses() + 1, today.plusDays(FIRST_INTERVAL_DAYS));
        }

        double ease = Math.max(MINIMUM_EASE_FACTOR, nextEaseFactor(current.getEaseFactor(), grade));
        int repetitions = current.getRepetitions() + 1;
        int interval = switch (repetitions) {
            case 1 -> FIRST_INTERVAL_DAYS;
            case 2 -> SECOND_INTERVAL_DAYS;
            // Round rather than truncate, so a 6-day interval at EF 1.3 grows to 8 and not 7.
            default -> Math.max(FIRST_INTERVAL_DAYS, (int) Math.round(current.getIntervalDays() * ease));
        };
        return new Outcome(ease, interval, repetitions, current.getLapses(), today.plusDays(interval));
    }

    // EF' = EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02)). A perfect 5 adds 0.1; a 4 leaves EF alone;
    // a 3 subtracts 0.14 — so merely scraping a pass still makes the card come back sooner.
    private static double nextEaseFactor(double easeFactor, int grade) {
        int shortfall = MAX_GRADE - grade;
        return easeFactor + (0.1 - shortfall * (0.08 + shortfall * 0.02));
    }

    // The computed next state. Applied to a ReviewState by ReviewService.
    public record Outcome(double easeFactor, int intervalDays, int repetitions, int lapses, LocalDate dueOn) {

        public void applyTo(ReviewState state, java.time.Instant reviewedAt) {
            state.setEaseFactor(easeFactor);
            state.setIntervalDays(intervalDays);
            state.setRepetitions(repetitions);
            state.setLapses(lapses);
            state.setDueOn(dueOn);
            state.setLastReviewedAt(reviewedAt);
        }
    }
}
