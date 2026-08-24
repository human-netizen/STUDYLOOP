package com.studyloop.backend.video;

import com.studyloop.backend.config.VideoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The wire to the renderer, and the only place in the backend that knows the sidecar exists.
//
// **Every failure here is a job failure, never an HTTP error.** By the time any of these methods
// run, the student's request returned 202 minutes ago. So the methods either answer or throw
// VideoWorkerException, and the runner turns that into a FAILED row with a reason — which is the
// entire difference between a queue and a fire-and-forget @Async call.
//
// A renderer that could call the model itself would be a second place where money is spent and a
// second place where usage is not recorded, which is why the only things crossing this wire are
// text and files.
@Slf4j
@Component
public class HttpVideoWorker implements VideoWorker {

    // How long a health probe may take before the renderer counts as down. Short, because this one
    // runs on a request thread: it is called by the library endpoint that the video page polls, and
    // by the admission check in front of a new job.
    private static final Duration PROBE_CONNECT = Duration.ofSeconds(2);
    private static final Duration PROBE_READ = Duration.ofSeconds(5);
    private static final Duration PROBE_TTL = Duration.ofSeconds(10);

    private final VideoProperties properties;
    // Two clients, because the two kinds of call have nothing in common but a hostname.
    //
    // **The render client's read timeout is the job timeout, and that is the load-bearing part.**
    // `RestClient.create()` has no timeouts at all, so a worker that accepts a connection and then
    // hangs — a Manim process wedged on a font lookup, a container being paused — would block the
    // single render thread forever. The job would sit in RENDERING with nothing running it, which
    // is exactly the state 21.1 built a startup sweep to clean up and exactly the state a *live*
    // process must never produce. A read timeout turns it into a FAILED row with a reason.
    private final RestClient probeClient;
    private final RestClient renderClient;
    // Written by whichever request probes first and read by every other; a stale read costs one
    // extra probe, which is why it is a plain volatile rather than a lock.
    private volatile Probe lastProbe;

    public HttpVideoWorker(VideoProperties properties) {
        this.properties = properties;
        this.probeClient = client(PROBE_CONNECT, PROBE_READ);
        this.renderClient = client(PROBE_CONNECT, properties.jobTimeout());
    }

    private static RestClient client(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(factory).build();
    }

    // Whether the sidecar is up and has the three tools it needs. Called before a job is accepted
    // and by the library endpoint, so the page can say "the renderer is not running" instead of
    // accepting a request that is going to fail in four seconds.
    //
    // Never throws. An unreachable worker is the expected state on a machine where Docker is not
    // started, and the caller wants a boolean, not an exception to swallow.
    //
    // **Cached for a few seconds, because this is on a request path and its callers are plural.**
    // The rail asks on every course page so it knows whether to draw the link at all, and the video
    // page asks again on load. Without the cache each of those is a round trip that can cost the
    // connect timeout, so the price of the renderer being *off* would be paid on pages that have
    // nothing to do with video. A ten-second-old "up" is good enough for both callers: neither is
    // deciding anything a fresh probe would decide differently, and the run itself reports the
    // truth if the renderer has gone since.
    @Override
    public Health health() {
        if (!hasWorker()) {
            return Health.down("No renderer is configured for this installation.");
        }
        Probe cached = lastProbe;
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(PROBE_TTL) < 0) {
            return cached.health();
        }
        Health health = probe();
        lastProbe = new Probe(health, Instant.now());
        return health;
    }

    private Health probe() {
        try {
            Health health = probeClient.get()
                    .uri(properties.workerUrl() + "/health")
                    .retrieve()
                    .body(Health.class);
            return health == null ? Health.down("The renderer answered with an empty body.") : health;
        } catch (RestClientException e) {
            return Health.down("The renderer is not reachable: " + rootMessage(e));
        }
    }

    private record Probe(Health health, Instant at) { }

    // One attempt at one animated scene. `ok` false is not an error — it is the common case this
    // whole phase is built around, and `layer` says which of the sandbox's three stopped it.
    @Override
    public RenderResult animate(UUID jobId, int sceneIndex, String code, Duration budget) {
        AnimateRequest request = new AnimateRequest(
                code, properties.width(), properties.height(), properties.fps(), budget.toSeconds());
        return post("/jobs/" + jobId + "/scenes/" + sceneIndex + "/animate", request, RenderResult.class);
    }

    // The fallback, and also the plan for scenes never meant to be animated. Drawn in the
    // product's palette by the worker, which is why the colours travel with the request rather
    // than living in the image: a slide that looks like a different application is a slide that
    // reads as an error.
    @Override
    public RenderResult slide(UUID jobId, int sceneIndex, String title, List<String> bullets) {
        SlideRequest request = new SlideRequest(
                title, bullets, properties.width(), properties.height());
        return post("/jobs/" + jobId + "/scenes/" + sceneIndex + "/slide", request, RenderResult.class);
    }

    // Speak one scene. The returned duration is measured from the rendered audio with ffprobe and
    // it is the authority on how long the scene runs — see VideoJobRunner.
    @Override
    public NarrationResult narrate(UUID jobId, int sceneIndex, String text, String voice) {
        return post("/jobs/" + jobId + "/scenes/" + sceneIndex + "/narrate",
                new NarrateRequest(text, voice), NarrationResult.class);
    }

    // Stitch the scenes, in order, into one file, and emit the caption track from the word
    // timings the narration step already collected.
    //
    // `sources` becomes the closing slide. It is passed here rather than drawn as an ordinary
    // scene because it is the one frame that must survive the file being downloaded and sent to
    // somebody: the per-scene citations live in the player's rail, and a video whose claims cannot
    // be traced once it leaves the page is the artifact §4 objected to.
    @Override
    public ComposeResult compose(UUID jobId, List<Integer> sceneOrder, List<String> sources) {
        ComposeRequest request = new ComposeRequest(
                sceneOrder, sources, properties.width(), properties.height(), properties.fps());
        return post("/jobs/" + jobId + "/compose", request, ComposeResult.class);
    }

    @Override
    public byte[] fetchVideo(UUID jobId) {
        return get("/jobs/" + jobId + "/video", byte[].class);
    }

    // Null when the render produced no word timings — a voice can decline to emit them, and a
    // missing caption track is a degraded video rather than a failed one.
    @Override
    public String fetchCaptions(UUID jobId) {
        try {
            return get("/jobs/" + jobId + "/captions", String.class);
        } catch (VideoWorkerException e) {
            log.warn("No caption track for job {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    // The scratch directory, once the bytes are safely on this side. Best effort: a worker that
    // has already restarted has nothing to clean, and a leaked temp directory inside a container
    // that gets recreated is not a leak that survives anything.
    @Override
    public void discard(UUID jobId) {
        if (!hasWorker()) {
            return;
        }
        try {
            probeClient.delete().uri(properties.workerUrl() + "/jobs/" + jobId).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("Could not discard worker scratch for job {}: {}", jobId, e.getMessage());
        }
    }

    private boolean hasWorker() {
        return properties.workerUrl() != null && !properties.workerUrl().isBlank();
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        requireWorker();
        try {
            T response = renderClient.post()
                    .uri(properties.workerUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
            if (response == null) {
                throw new VideoWorkerException("The renderer returned an empty response for " + path + ".");
            }
            return response;
        } catch (RestClientException e) {
            throw new VideoWorkerException("The renderer failed at " + path + ": " + rootMessage(e), e);
        }
    }

    private <T> T get(String path, Class<T> responseType) {
        requireWorker();
        try {
            T response = renderClient.get()
                    .uri(properties.workerUrl() + path)
                    .retrieve()
                    .body(responseType);
            if (response == null) {
                throw new VideoWorkerException("The renderer returned nothing for " + path + ".");
            }
            return response;
        } catch (RestClientException e) {
            throw new VideoWorkerException("The renderer failed at " + path + ": " + rootMessage(e), e);
        }
    }

    private void requireWorker() {
        if (!hasWorker()) {
            throw new VideoWorkerException("No renderer is configured for this installation.");
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    // ── the wire ────────────────────────────────────────────────────────────────────────────
    //
    // Request shapes only: the responses are the interface's records, so the stub in the tests and
    // the container answer with the same types. Records rather than a shared schema file, because
    // the other side of this wire is Python and there is no shared schema file to have. Flat
    // shapes whose JSON names are the field names — a contract two languages can be checked
    // against by reading one screen.

    private record AnimateRequest(String code, int width, int height, int fps, long budgetSeconds) { }

    private record SlideRequest(String title, List<String> bullets, int width, int height) { }

    private record NarrateRequest(String text, String voice) { }

    private record ComposeRequest(List<Integer> scenes, List<String> sources,
                                  int width, int height, int fps) { }
}
