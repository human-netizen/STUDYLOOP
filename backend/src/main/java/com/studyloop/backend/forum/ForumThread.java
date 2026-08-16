package com.studyloop.backend.forum;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.course.CourseSpace;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

// A question put to the class, usually one the assistant refused to answer. The forum is the
// second half of a refusal: chat says "not in the materials", and this is where that stops being
// a dead end.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "forum_threads")
public class ForumThread {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpace courseSpace;

    // The refusal behind this thread, if there was one. A plain column rather than a mapped
    // relationship because question_events is written and read entirely through native SQL (it
    // holds a pgvector column Hibernate cannot map) and has no entity to point at.
    @Column(name = "question_event_id")
    private UUID questionEventId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ForumThreadStatus status = ForumThreadStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "accepted_answer_id")
    private UUID acceptedAnswerId;

    // The corpus document grown from the accepted answer. Set only when that write succeeded, so
    // "this is in the materials" is something the UI reads rather than infers from the status.
    @Column(name = "answer_document_id")
    private UUID answerDocumentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
