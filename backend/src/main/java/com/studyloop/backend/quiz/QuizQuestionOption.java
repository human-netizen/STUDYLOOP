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

// One selectable choice of a MULTIPLE_CHOICE question. The (question, option_index) unique
// constraint fixes the order the choices are shown and the index the answer key refers to.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quiz_question_options", uniqueConstraints =
        @UniqueConstraint(name = "uq_quiz_option_index", columnNames = {"question_id", "option_index"}))
public class QuizQuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private QuizQuestion question;

    // 0-based position among the question's choices.
    @Column(name = "option_index", nullable = false)
    private int optionIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String text;
}
