package com.studyloop.backend.video;

// Where a video job is in its life (Phase 21.1).
//
// The same shape as DocumentStatus on purpose: a linear walk with two terminal failures, so the
// frontend polls it with the idiom the upload page already has and the backend writes it with the
// idiom DocumentStatusService already has. A second polling protocol would be a second thing to
// get subtly wrong, and the thing it gets wrong is always the terminal state.
//
// The one addition is REFUSED, and it is not a failure. It means the confidence gate looked at
// what the corpus has on this topic and said no — before the worker was contacted, before a frame
// was rendered, at the cost of one embedding. FAILED means we tried and something broke; REFUSED
// means we declined and nothing was spent. Collapsing them would tell a student their video
// crashed when in fact their course has no material on what they asked for, which is the one piece
// of information the answer actually contains.
public enum VideoJobStatus {

    // Accepted, waiting for the single render slot. Not "PENDING": this is a queue position, and
    // a job can sit here for the length of somebody else's render.
    QUEUED,
    // Retrieving, gating, and asking the model for a script and a scene plan. Every model call in
    // the job happens in this state except the fix loop's.
    PLANNING,
    // The sidecar is running Manim, drawing slides and speaking narration. The long one.
    RENDERING,
    // ffmpeg is stitching the scenes together. Short, and separate from RENDERING because it is
    // the point after which nothing can fall back any more — the counters are final here.
    COMPOSING,

    READY,
    FAILED,
    // The corpus cannot support the topic. Terminal, cheap, and offered with Phase 20.2's two
    // buttons rather than as an error.
    REFUSED;

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == REFUSED;
    }

    // Whether a job in this state was left mid-flight by a process that is no longer running.
    // Read only by the startup sweep — see VideoJobReconciler for why the alternative is a job
    // that says 40% forever.
    public boolean isUnfinished() {
        return !isTerminal();
    }
}
