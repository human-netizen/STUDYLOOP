package com.studyloop.backend.guide;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.document.Language;
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

import java.time.Instant;
import java.util.UUID;

// One request for a study guide, and everything that happened to it (Phase 22).
//
// **The counters on this row are the phase's evidence, not decoration.** 22.4 makes a claim about
// what a guide costs — "one planning call plus one call per section" — and the honest place for a
// claim like that is a column that can be read back, in the same way Phase 21 put `modelCalls` on
// a scene rather than in a log line. `sectionsPlanned` against `sectionsWritten` is the other
// half: the difference between them is how much of this topic the course does not cover.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "study_guides")
public class StudyGuide {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpace courseSpace;

    // Who asked, and therefore who may read it. Same rule as a video, for the same reason: the
    // guide is grounded on what this member can see, private notes included.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StudyGuideStatus status = StudyGuideStatus.QUEUED;

    // The language of the material, written back once the planner has seen what it retrieved.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Language language = Language.ENGLISH;

    @Column(name = "sections_planned", nullable = false)
    private int sectionsPlanned;

    @Column(name = "sections_written", nullable = false)
    private int sectionsWritten;

    @Column(name = "model_calls", nullable = false)
    private int modelCalls;

    @Column(columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public StudyGuide(CourseSpace courseSpace, User requestedBy, String topic) {
        this.courseSpace = courseSpace;
        this.requestedBy = requestedBy;
        this.topic = topic;
    }
}
