package com.studyloop.backend.video;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// The renderer, stubbed — which is the point of VideoWorker being an interface.
//
// Everything Phase 21 is actually accountable for lives above that seam: the queue and its states,
// the confidence gate refusing before anything is rendered, the fallback being counted and
// explained, the citations, the startup sweep, the daily cap and the visibility rules. All of it is
// deterministic Java and all of it is tested in seconds here, with no container, no ffmpeg and no
// Manim.
//
// What this stub deliberately cannot tell anybody is whether a model writes *good* Manim, or
// whether the sandbox holds. The first is a judgement about a provider; the second is asserted
// where it lives, in video-worker/tests/test_sandbox.py, against real forks and real rlimits. A
// Java stub that "verified" the sandbox would be verifying the stub.
//
// **The bean is declared in StubAiConfig rather than in a config of its own, and that is a
// constraint rather than a preference.** Each distinct test configuration gets its own cached
// ApplicationContext, and each context opens its own Hikari pool against a session pooler that caps
// clients at 15. A `@TestConfiguration` imported only by the video tests is one more context and
// one more pool — which is exactly the failure it caused when it was written that way: eight pools
// of two against a cap of fifteen, and the video context could not start.
public class RecordingVideoWorker implements VideoWorker {

    public static final byte[] MP4 = "fake-mp4-bytes".getBytes(StandardCharsets.UTF_8);
    public static final String VTT = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nHello.\n";

    // Every call, in order, as "verb:index" — so a test can assert not just what happened but that
    // nothing happened. "The worker was never contacted" is the assertion the whole
    // refuse-before-rendering design exists to make true, and it needs a log rather than a counter.
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final AtomicInteger animateAttempts = new AtomicInteger();

    public volatile boolean up = true;
    // What the next animate attempt does. Default: it works. A test that wants the fallback path
    // sets a layer here and gets a scene that fell back for a stated reason.
    public volatile String animateFailsAtLayer = null;
    public volatile boolean narrationFails = false;
    public volatile boolean composeFails = false;
    public volatile boolean captions = true;
    public volatile double sceneSeconds = 12.5;

    public List<String> callsMatching(String prefix) {
        List<String> matching = new ArrayList<>();
        for (String call : calls) {
            if (call.startsWith(prefix)) {
                matching.add(call);
            }
        }
        return matching;
    }

    @Override
    public Health health() {
        calls.add("health");
        return up ? Health.up() : Health.down("The renderer is not reachable.");
    }

    @Override
    public RenderResult animate(UUID jobId, int sceneIndex, String code, Duration budget) {
        calls.add("animate:" + sceneIndex);
        animateAttempts.incrementAndGet();
        String layer = animateFailsAtLayer;
        if (layer == null) {
            return new RenderResult(true, "OK", "");
        }
        return new RenderResult(false, layer, "`import os` is not permitted in a generated scene.");
    }

    @Override
    public RenderResult slide(UUID jobId, int sceneIndex, String title, List<String> bullets) {
        calls.add("slide:" + sceneIndex);
        return new RenderResult(true, "OK", "");
    }

    @Override
    public NarrationResult narrate(UUID jobId, int sceneIndex, String text, String voice) {
        calls.add("narrate:" + sceneIndex + ":" + voice);
        return narrationFails
                ? new NarrationResult(false, 0, "The speech service returned no audio.")
                : new NarrationResult(true, sceneSeconds, "");
    }

    @Override
    public ComposeResult compose(UUID jobId, List<Integer> sceneOrder, List<String> sources) {
        calls.add("compose:" + sceneOrder.size() + ":sources=" + sources.size());
        return composeFails
                ? new ComposeResult(false, 0, false, "The scenes could not be joined together.")
                : new ComposeResult(true, sceneSeconds * sceneOrder.size(), captions, "");
    }

    @Override
    public byte[] fetchVideo(UUID jobId) {
        calls.add("fetchVideo");
        return MP4;
    }

    @Override
    public String fetchCaptions(UUID jobId) {
        calls.add("fetchCaptions");
        return captions ? VTT : null;
    }

    @Override
    public void discard(UUID jobId) {
        calls.add("discard");
    }

    // Called from @BeforeEach, because the context — and therefore this bean — is shared with every
    // other test class that imports StubAiConfig.
    public void reset() {
        calls.clear();
        animateAttempts.set(0);
        up = true;
        animateFailsAtLayer = null;
        narrationFails = false;
        composeFails = false;
        captions = true;
        sceneSeconds = 12.5;
    }
}
