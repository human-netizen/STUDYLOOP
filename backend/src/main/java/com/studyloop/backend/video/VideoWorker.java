package com.studyloop.backend.video;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

// What the backend is allowed to ask the renderer for: text in, files out.
//
// An interface with one production implementation, for the reason VisionClient and EmbeddingClient
// are interfaces here — the seam is what lets the whole feature be tested without the container.
// Everything above this line is deterministic Java: the queue, the fallback accounting, the
// citation links, the refusal path and the startup sweep. Everything below it is ffmpeg and Manim.
// Putting the boundary here means a change to the queue is a test, not a two-gigabyte image build.
//
// **The renderer never receives a credential, a database URL, or an id it could look anything up
// with.** That is not enforced by this interface, but it is visible in it: there is nothing in
// these signatures a renderer could use to reach anything. See the worker's own module docstring
// for the other half of the argument.
public interface VideoWorker {

    // Whether the renderer is up and has ffmpeg and Manim. Never throws — an absent renderer is
    // the expected state on a machine where Docker is not running, and the caller wants a boolean.
    Health health();

    // One attempt at one generated scene. `ok` false is not an error; `layer` says which of the
    // sandbox's three stopped it.
    RenderResult animate(UUID jobId, int sceneIndex, String code, Duration budget);

    RenderResult slide(UUID jobId, int sceneIndex, String title, List<String> bullets);

    // Speak one scene. The returned duration is measured from the rendered audio and is the
    // authority on how long the scene runs.
    NarrationResult narrate(UUID jobId, int sceneIndex, String text, String voice);

    // Stitch the scenes, in order, and emit the caption track from the word timings narration
    // already collected. `sources` becomes the closing slide.
    ComposeResult compose(UUID jobId, List<Integer> sceneOrder, List<String> sources);

    byte[] fetchVideo(UUID jobId);

    // Null when the render produced no word timings. A missing caption track is a degraded video,
    // not a failed one.
    String fetchCaptions(UUID jobId);

    // The scratch directory, once the bytes are on this side. Best effort.
    void discard(UUID jobId);

    record Health(boolean ready, String ffmpeg, String manim, String detail) {

        public static Health down(String detail) {
            return new Health(false, null, null, detail);
        }

        public static Health up() {
            return new Health(true, "ffmpeg", "manim", "ready");
        }
    }

    // `layer` is the sandbox stage that stopped it: REJECTED (the AST allow-list), COMPILE, KILLED
    // (a resource limit or the process-group kill), TIMEOUT, or RENDER for a Manim error. Naming
    // the layer is what makes the hostile-fixture suite assertable — a test that only knew the
    // render failed could not tell a blocked import from a syntax error.
    record RenderResult(boolean ok, String layer, String detail) { }

    record NarrationResult(boolean ok, double durationSeconds, String detail) { }

    record ComposeResult(boolean ok, double durationSeconds, boolean captions, String detail) { }
}
