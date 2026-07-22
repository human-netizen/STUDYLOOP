package com.studyloop.backend.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// One question in a quiz. For MULTIPLE_CHOICE the choices live in quiz_question_options and the
// key is correctOptionIndex; expectedAnswer is null. For SHORT_ANSWER there are no options and
// expectedAnswer holds the reference answer the grader compares against. explanation is shown
// after grading either way. The (quiz, question_index) unique constraint keeps ordering stable.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quiz_questions", uniqueConstraints =
        @UniqueConstraint(name = "uq_quiz_question_index", columnNames = {"quiz_id", "question_index"}))
public class QuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    // 0-based position within the quiz.
    @Column(name = "question_index", nullable = false)
    private int questionIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionType type;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    // Set for MULTIPLE_CHOICE only: the 0-based index of the correct option; null otherwise.
    @Column(name = "correct_option_index")
    private Integer correctOptionIndex;

    // Set for SHORT_ANSWER only: the reference answer the grader judges against; null otherwise.
    @Column(name = "expected_answer", columnDefinition = "text")
    private String expectedAnswer;

    @Column(columnDefinition = "text")
    private String explanation;
}
