package com.studyloop.backend.video.dto;

import com.studyloop.backend.document.Language;
import com.studyloop.backend.video.VideoJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A job, as the page polls it.
//
// The three scene counters are here rather than derived on the client because they are the
// feature's honesty: "6 scenes, 4 animated, 2 fell back to slides" is a sentence the UI states
// while the job is still running, and a client computing it from the scene list would show
// nothing until the list existed.
public record VideoJobResponse(
        UUID id,
        UUID courseId,
        String topic,
        Language language,
        VideoJobStatus status,
        // The human sentence under the progress bar, null before anything has happened.
        String stage,
        int scenesTotal,
        int scenesAnimated,
        int scenesFallback,
        Double durationSeconds,
        // Whether there is a caption track to attach. False for a job rendered before captions
        // existed, or one whose narration produced no word timings.
        boolean hasCaptions,
        // Why it stopped. For REFUSED this is the confidence gate speaking, and the client renders
        // it with Phase 20.2's two buttons rather than as an error.
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        // Empty until planning finishes. Present from then on, so the page can show what is being
        // rendered while it is being rendered.
        List<VideoSceneResponse> scenes
) { }
