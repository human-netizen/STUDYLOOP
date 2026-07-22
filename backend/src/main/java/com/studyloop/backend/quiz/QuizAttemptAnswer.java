package com.studyloop.backend.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

// The user's response to one question within an attempt, plus whether it was judged correct.
// selectedOptionIndex is set for multiple-choice, answerText for short-answer; the other is null.
// The (attempt, question) unique constraint keeps at most one answer per question per attempt.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quiz_attempt_answers", uniqueConstraints =
        @UniqueConstraint(name = "uq_attempt_answer_question", columnNames = {"attempt_id", "question_id"}))
public class QuizAttemptAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private QuizAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private QuizQuestion question;

    @Column(name = "selected_option_index")
    private Integer selectedOptionIndex;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @Column(nullable = false)
    private boolean correct;
}
