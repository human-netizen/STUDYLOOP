package com.studyloop.backend.video;

// What a scene actually became (Phase 21.4).
//
// Not what it was planned as. The plan is an intention — the model asked for an animation and the
// sandbox may have refused it, the compiler may have rejected it, or the clock may have run out —
// and this enum is the measurement taken afterwards. AddNewFeature.md §4's objection to ZenLearn's
// pipeline was precisely that the two were never distinguished: it falls back to a static slide
// and says nothing, so a job that produced seven slides and a job that produced seven animations
// look identical from the outside.
public enum SceneRendering {

    // Manim ran, produced frames, and they are in the film.
    ANIMATED,
    // A Pillow slide in the product's palette. A legitimate output, drawn to look like the
    // application rather than like an error — but counted, and shown, and averaged in the report.
    SLIDE
}
