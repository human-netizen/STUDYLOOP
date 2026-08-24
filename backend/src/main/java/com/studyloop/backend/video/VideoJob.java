package com.studyloop.backend.video;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

// One request for a video, and everything that happened to it.
//
// The row is written by Spring and only by Spring. The renderer is a separate process in a
// different language and it has no database credentials at all — it is handed a scene plan as
// JSON and hands back files. That boundary is drawn here rather than at the convenient place (a
// shared connection) because a status column with two writers is a status column that eventually
// disagrees with itself, and Phase 20 found one that had been doing so since Phase 16.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_jobs")
public class VideoJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpace courseSpace;

    // Who asked, and therefore who may watch. 21.2 grounds the script on what this member can
    // read — their own OWNER-visibility notes included — so the finished file inherits their
    // visibility rather than the course's. Sharing one with the class would need a second
    // grounding pass against course-visible material only, which is a phase and not a checkbox.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    // What the narration was spoken in. Phase 19.1's column doing a second job: a Bangla course
    // gets a bn-BD voice, and the alternative — a Bangla script read aloud by an English voice —
    // is worse than no narration, because it sounds like a bug in the product rather than like a
    // missing feature.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Language language = Language.ENGLISH;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoJobStatus status = VideoJobStatus.QUEUED;

    // The sentence under the progress bar. Free text, because it is a message to a person.
    @Column(columnDefinition = "text")
    private String stage;

    @Column(name = "scenes_total", nullable = false)
    private int scenesTotal;

    @Column(name = "scenes_animated", nullable = false)
    private int scenesAnimated;

    @Column(name = "scenes_fallback", nullable = false)
    private int scenesFallback;

    // Relative to the videos root, "{courseId}/{jobId}.mp4" — DocumentStorageService's convention,
    // so the deploy phase inherits one volume decision rather than two.
    @Column(name = "output_path", columnDefinition = "text")
    private String outputPath;

    @Column(name = "captions_path", columnDefinition = "text")
    private String captionsPath;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    // The reason it stopped, for both terminal failures. A refusal carries the gate's own wording,
    // so the student reads the sentence chat would have given them.
    @Column(columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // When the render actually started, as opposed to when it was asked for. The gap between the
    // two is queue time, and keeping them apart is what lets the phase report say "four minutes of
    // wall clock per finished minute" without silently counting the time this job spent waiting
    // behind another member's render.
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public VideoJob(CourseSpace courseSpace, User requestedBy, String topic, Language language) {
        this.courseSpace = courseSpace;
        this.requestedBy = requestedBy;
        this.topic = topic;
        this.language = language;
    }
}
