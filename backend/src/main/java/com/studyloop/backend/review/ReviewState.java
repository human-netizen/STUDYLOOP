package com.studyloop.backend.review;

import com.studyloop.backend.flashcard.Flashcard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// The SM-2 scheduling state of one flashcard: strictly one row per card, which is why the card's
// id is also this row's primary key (@MapsId — a shared-primary-key one-to-one, no surrogate id
// and no separate unique constraint needed).
//
// The numbers themselves are computed by Sm2Scheduler; this class only stores them.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "review_states")
public class ReviewState {

    @Id
    @Column(name = "flashcard_id")
    private UUID flashcardId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flashcard_id")
    private Flashcard flashcard;

    @Column(name = "ease_factor", nullable = false)
    private double easeFactor = Sm2Scheduler.INITIAL_EASE_FACTOR;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(nullable = false)
    private int repetitions;

    @Column(nullable = false)
    private int lapses;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    // A brand-new card is due immediately — you made it to study it now, not in six days.
    public static ReviewState freshFor(Flashcard card, LocalDate today) {
        ReviewState state = new ReviewState();
        state.setFlashcard(card);
        state.setDueOn(today);
        return state;
    }
}
