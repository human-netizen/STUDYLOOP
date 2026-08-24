package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Phase 21 — what the video generator is allowed to cost, and where the thing that does the work
// lives.
//
// **Off by default, and that is a product decision rather than a caution.** The renderer is an
// optional sidecar container. An installation without it must show nothing — not a button that
// fails, not a spinner that never resolves — because the failure mode of an optional feature that
// advertises itself is worse than the absence of the feature. Phase 15.1 settled this for a
// missing vision key and the rule is the same one: the flag decides whether the UI is drawn, and
// the endpoint answers 503 with a reason for anyone who calls it anyway.
//
// **Every number here is a wall-clock or money limit**, which is why they are configuration and
// not constants. The right value for `scene-budget` is a property of the machine the worker runs
// on, and a laptop and a CI runner disagree about it by a factor of five.
@ConfigurationProperties(prefix = "studyloop.video")
public record VideoProperties(

        // The whole feature. Off → no button, no endpoint, no worker contacted.
        boolean enabled,

        // Where the sidecar answers. Absent or unreachable is not a crash: `enabled` may be true
        // on a machine where Docker is not running, and the honest outcome then is a job that
        // fails immediately with "the renderer is not available" rather than one that hangs.
        String workerUrl,

        // Filesystem root for finished videos, mirroring studyloop.storage.documents-dir.
        // Relative in dev, a mounted volume in the cloud — one volume decision, inherited.
        String videosDir,

        // **One render at a time, globally.** Not a pool: a Manim render saturates the cores of
        // the machine it is on, so two concurrent jobs finish later than the same two run
        // serially and both look broken while they do it. A second request is queued, and the
        // queue position is a state the student can see.
        int maxConcurrent,

        // Per-member, per rolling day, across all courses. The cap exists because one video is
        // roughly an order of magnitude more expensive than one chat answer in model calls, and
        // several orders of magnitude more expensive in wall clock. Refusals are not counted:
        // asking about something the corpus does not cover costs one embedding and should not
        // consume an allowance.
        int dailyCapPerUser,

        // The upper bounds on a single film. Six scenes at 2-3 minutes is the length at which a
        // narrated explanation is still watched; past that a student scrubs, and everything past
        // the scrub was rendered for nobody.
        int maxScenes,
        // At most this many of them may attempt Manim. The rest are slides by design rather than
        // by failure, which keeps the worst case bounded: the fix loop is the expensive path, and
        // an all-animated six-scene job is 20 model calls and twenty minutes.
        int maxAnimatedScenes,
        int targetSeconds,

        // How long one scene gets, start to finish, across all its render attempts. The fix loop
        // shares this budget rather than getting a fresh timeout per attempt — ZenLearn retries
        // three times at 120s each with no overall bound, which is how a job takes twenty minutes
        // and still produces slides.
        Duration sceneBudget,
        // How many times the model may be shown its own stderr and asked to try again. Two, and
        // the reason it is not five is that the third fix almost never lands and always bills.
        int maxFixes,
        // The whole job, including planning and composition. A job that exceeds it is FAILED with
        // that as the reason, so the queue cannot be blocked by one pathological render.
        Duration jobTimeout,

        // 1280x720 at 30fps. A 1080p Manim render is minutes *per scene* on a laptop, and this
        // phase reports wall clock per finished minute — the resolution is a term in that number.
        int width,
        int height,
        int fps,

        // How many chunks the script is written from. The same k as a chat turn, for the reason
        // CorpusAnswerService gives: two features grounded on different amounts of context is a
        // difference nobody can see and everybody has to explain.
        int retrievalK,

        Voices voices
) {

    // The edge-tts voice per language. Two entries because Language has two values, and a map
    // keyed by an ISO code would be claiming a precision Phase 19.1 deliberately refused.
    public record Voices(String english, String bangla) { }
}
