package com.studyloop.backend.flashcard;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.course.CourseSpace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

// A personal study card scoped to a course and owned by its creator. front is the prompt side,
// back the answer side. sourceDocumentId/sourcePage record where a generated card came from
// (null for cards saved from a chat exchange); the source id is a plain column, not a mapped
// relationship, so a deleted document just nulls it out (DB on delete set null) without needing
// a fetch to read the card.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "flashcards")
public class Flashcard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpace courseSpace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, columnDefinition = "text")
    private String front;

    @Column(nullable = false, columnDefinition = "text")
    private String back;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "source_page")
    private Integer sourcePage;

    // Set when the card was auto-created from a quiz question the owner got wrong (Phase 8.1).
    // A partial unique index on (created_by, source_quiz_question_id) makes that enrolment
    // idempotent: missing the same question on a retake reuses the existing card.
    @Column(name = "source_quiz_question_id")
    private UUID sourceQuizQuestionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
