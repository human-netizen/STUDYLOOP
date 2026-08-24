package com.studyloop.backend.video;

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

// One scene of a finished video, and the record of how it got that way.
//
// These rows are the phase's evidence rather than its output — the mp4 is the output. The question
// this feature has to survive is not "does it make a video", it is "what happens when the model
// writes code that does not compile, or code that tries to open a file", and the answer is a row
// here naming the layer that stopped it, next to the slide that was drawn instead.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_scenes")
public class VideoScene {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private VideoJob job;

    @Column(name = "scene_index", nullable = false)
    private int sceneIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    // What the voice says over this scene. Kept because it is also what the caption track holds,
    // and because a scene the student can read is a scene they can report a mistake in.
    @Column(nullable = false, columnDefinition = "text")
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(name = "rendered_as", nullable = false, length = 20)
    private SceneRendering renderedAs = SceneRendering.SLIDE;

    // Null for a scene that was animated, and for one that was planned as a slide from the start.
    // Set only when an animation was attempted and lost: the layer that stopped it and the last
    // thing the toolchain said, truncated. This is the field the "no silent fallback" claim rests
    // on, and it is why the fallback count on the job can be explained rather than merely shown.
    @Column(name = "fallback_reason", columnDefinition = "text")
    private String fallbackReason;

    // Model calls spent on this scene alone: 1 for a first attempt that worked, up to 3 when the
    // fix loop ran twice. Summed over the job, this is the difference between the 14-call worst
    // case the plan budgets for and what a video costs in practice.
    @Column(name = "model_calls", nullable = false)
    private int modelCalls;

    // Where the generated module was kept, relative to the videos root. A failed render is
    // worthless as a bug report without the code that failed, and the code is two kilobytes.
    @Column(name = "code_path", columnDefinition = "text")
    private String codePath;

    // Measured from the rendered narration audio, never from the model's estimate — see
    // VideoJobRunner for why the estimate is a planning hint and never a render input.
    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public VideoScene(VideoJob job, int sceneIndex, String title, String narration) {
        this.job = job;
        this.sceneIndex = sceneIndex;
        this.title = title;
        this.narration = narration;
    }

    public boolean animated() {
        return renderedAs == SceneRendering.ANIMATED;
    }
}
