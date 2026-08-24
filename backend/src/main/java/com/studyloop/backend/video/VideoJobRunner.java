package com.studyloop.backend.video;

import com.studyloop.backend.config.VideoProperties;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.video.VideoPlanner.PlannedScene;
import com.studyloop.backend.video.VideoPlanner.VideoPlan;
import com.studyloop.backend.video.VideoWorker.ComposeResult;
import com.studyloop.backend.video.VideoWorker.NarrationResult;
import com.studyloop.backend.video.VideoWorker.RenderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The job, from a topic to a file. Runs on the single-slot video executor, off the request thread.
//
// **This is the part AddNewFeature.md §4 said did not exist**, and the shape of it is the answer:
// a state machine whose every transition is committed as it happens, a per-scene budget that
// cannot be exceeded by retrying, a fallback that is counted, and one terminal state for every way
// the job can end. An @Async method with a status field would have looked like this from the
// outside and behaved differently in exactly the case that matters — the one where something goes
// wrong in the middle.
//
// **One render at a time, and the limit is the executor, not a check.** A semaphore or a
// count-then-decide would be a race with the next request; a pool with one thread is a queue by
// construction, and a job waiting behind another job is QUEUED rather than lost.
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoJobRunner {

    // What a student is told when the corpus cannot support the topic. Deliberately chat's
    // sentence with the video half added: the same claim, and the student who has seen it in chat
    // recognises it rather than reading it as a different failure.
    static final String NOT_IN_MATERIALS =
            "I don't have that in this course's materials, so there is nothing to make a video "
            + "from. Try rephrasing, or upload a document that covers it.";

    private final VideoJobRepository jobRepository;
    private final VideoJobStatusService statusService;
    private final VideoPlanner planner;
    private final ManimSceneGenerator sceneGenerator;
    private final VideoWorker worker;
    private final VideoStorageService storage;
    private final VideoProperties properties;

    // Fire-and-forget from the controller's point of view; everything that can go wrong from here
    // on has a row to be written onto. Nothing thrown out of this method reaches anybody, which is
    // why the catch is total rather than selective — an exception escaping onto the executor
    // thread would be logged by the pool and lost, leaving a row that says RENDERING until the
    // next restart sweeps it.
    //
    // Called from VideoJobListener, which owns the @Async and the AFTER_COMMIT ordering.
    public void run(UUID jobId, UUID actorId) {
        Instant deadline = Instant.now().plus(properties.jobTimeout());
        try {
            execute(jobId, actorId, deadline);
        } catch (RuntimeException e) {
            log.warn("Video job {} failed", jobId, e);
            safelyFail(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            worker.discard(jobId);
        }
    }

    private void execute(UUID jobId, UUID actorId, Instant deadline) {
        VideoJob job = jobRepository.findById(jobId).orElseThrow(() -> new VideoJobNotFoundException(jobId));
        // A job the startup sweep already failed, or one somebody cancelled while it queued. The
        // check is cheap and the alternative is a render nobody is waiting for.
        if (job.getStatus() != VideoJobStatus.QUEUED) {
            return;
        }
        UUID courseId = job.getCourseSpace().getId();
        String topic = job.getTopic();

        statusService.markStage(jobId, VideoJobStatus.PLANNING, "Reading your course materials");
        Optional<VideoPlan> planned = planner.plan(actorId, courseId, topic);
        if (planned.isEmpty()) {
            // Before the worker was contacted, before a frame existed, at the cost of one
            // embedding. This ordering is the whole of 21.2's argument.
            statusService.markRefused(jobId, NOT_IN_MATERIALS);
            return;
        }
        VideoPlan plan = planned.get();

        List<UUID> sceneIds = statusService.saveScenes(jobId, plan.scenes());
        statusService.markStage(jobId, VideoJobStatus.RENDERING,
                "Rendering scene 1 of " + plan.scenes().size());

        List<Integer> composed = new ArrayList<>();
        for (int i = 0; i < plan.scenes().size(); i++) {
            requireTime(deadline, "the job took longer than " + properties.jobTimeout().toMinutes() + " minutes");
            PlannedScene scene = plan.scenes().get(i);
            statusService.markStage(jobId,
                    "Rendering scene " + (i + 1) + " of " + plan.scenes().size());
            if (renderScene(jobId, courseId, sceneIds.get(i), scene, plan.language(), deadline)) {
                composed.add(scene.index());
            }
        }
        if (composed.isEmpty()) {
            throw new VideoWorkerException("Every scene failed to render.");
        }

        statusService.markStage(jobId, VideoJobStatus.COMPOSING, "Putting the scenes together");
        ComposeResult composition = worker.compose(jobId, composed, sources(plan));
        if (!composition.ok()) {
            throw new VideoWorkerException("Composition failed: " + composition.detail());
        }

        String outputPath = storage.storeVideo(courseId, jobId, worker.fetchVideo(jobId));
        String captionsPath = null;
        if (composition.captions()) {
            String vtt = worker.fetchCaptions(jobId);
            if (vtt != null && !vtt.isBlank()) {
                captionsPath = storage.storeCaptions(courseId, jobId, vtt);
            }
        }
        statusService.markReady(jobId, outputPath, captionsPath, composition.durationSeconds());
    }

    // One scene: speak it, then try to animate it, then fall back if that loses. Returns whether
    // the scene has anything to contribute to the film.
    //
    // **Narration first, and not for convenience.** The audio's measured length is what the scene
    // is built to fit at composition time, so a scene whose narration cannot be produced has no
    // duration to build against and is dropped rather than guessed at. ZenLearn does the opposite
    // — it loops a still under `-shortest` against the model's own duration estimate — and the
    // symptom is narration cut off mid-sentence whenever the estimate was low.
    private boolean renderScene(UUID jobId, UUID courseId, UUID sceneId, PlannedScene scene,
                                Language language, Instant deadline) {
        NarrationResult narration = worker.narrate(jobId, scene.index(), scene.narration(), voice(language));
        if (!narration.ok()) {
            statusService.markScene(sceneId, SceneRendering.SLIDE,
                    "Narration failed: " + narration.detail(), 0, null, null);
            return false;
        }
        double duration = narration.durationSeconds();

        if (scene.animate()) {
            Attempt attempt = animate(jobId, courseId, scene, deadline);
            if (attempt.succeeded()) {
                statusService.markScene(sceneId, SceneRendering.ANIMATED, null,
                        attempt.modelCalls(), duration, attempt.codePath());
                return true;
            }
            // The slide is drawn from bullets the planner produced up front, which is why a
            // failure here costs one worker call and no model call: there is already something to
            // put on screen.
            RenderResult slide = worker.slide(jobId, scene.index(), scene.title(), scene.bullets());
            statusService.markScene(sceneId, SceneRendering.SLIDE, attempt.reason(),
                    attempt.modelCalls(), duration, attempt.codePath());
            return slide.ok();
        }

        RenderResult slide = worker.slide(jobId, scene.index(), scene.title(), scene.bullets());
        // No fallback reason: this scene was never going to be animated, and writing one would
        // make the report's fallback count mean two different things.
        statusService.markScene(sceneId, SceneRendering.SLIDE, null, 0, duration, null);
        return slide.ok();
    }

    // The fix loop. At most 1 + maxFixes attempts, all of them inside one wall-clock budget for
    // the scene — so a model that keeps producing code that hangs the renderer costs a bounded
    // amount of time rather than maxFixes times the render timeout.
    private Attempt animate(UUID jobId, UUID courseId, PlannedScene scene, Instant deadline) {
        Instant sceneDeadline = earliest(Instant.now().plus(properties.sceneBudget()), deadline);
        String code = null;
        String reason = null;
        String codePath = null;
        int modelCalls = 0;

        for (int attempt = 0; attempt <= properties.maxFixes(); attempt++) {
            Duration remaining = Duration.between(Instant.now(), sceneDeadline);
            if (remaining.isNegative() || remaining.toSeconds() < 5) {
                return new Attempt(false, modelCalls, codePath,
                        reason == null ? "TIMEOUT — the scene ran out of its time budget." : reason);
            }
            code = attempt == 0 ? sceneGenerator.generate(scene) : sceneGenerator.fix(code, reason);
            modelCalls++;
            // Kept whether it renders or not, and each attempt overwrites the last: a failed render
            // is worthless as a bug report without the code that failed, and the attempt worth
            // reading is the final one — the earlier drafts are what the model has already been
            // shown to be wrong.
            codePath = storage.storeSceneCode(courseId, jobId, scene.index(), code);

            RenderResult result = worker.animate(jobId, scene.index(), code, remaining);
            if (result.ok()) {
                return new Attempt(true, modelCalls, codePath, null);
            }
            reason = result.layer() + " — " + result.detail();
            log.debug("Scene {} of job {} rejected at {}: {}", scene.index(), jobId,
                    result.layer(), result.detail());
            // A scene the allow-list rejected is not retried past the first fix for a different
            // reason than a compile error is: the model wrote something forbidden, and the honest
            // read of a second rejection is that it will keep doing so.
            if ("REJECTED".equals(result.layer()) && attempt >= 1) {
                break;
            }
        }
        return new Attempt(false, modelCalls, codePath, reason);
    }

    // The closing slide's list: which documents this video was made from, deduplicated and in the
    // order retrieval ranked them. Filenames and page numbers rather than chunk ids, because the
    // audience for this frame is a person who has the PDF.
    private static List<String> sources(VideoPlan plan) {
        return plan.chunks().stream()
                .map(chunk -> chunk.pageNumber() == null
                        ? chunk.filename()
                        : chunk.filename() + ", p." + chunk.pageNumber())
                .distinct()
                .limit(4)
                .toList();
    }

    private String voice(Language language) {
        return language == Language.BANGLA
                ? properties.voices().bangla()
                : properties.voices().english();
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static void requireTime(Instant deadline, String message) {
        if (Instant.now().isAfter(deadline)) {
            throw new VideoWorkerException(message + ".");
        }
    }

    // The failure path must not be able to fail: a job that throws while recording that it threw
    // is a row stuck in RENDERING, which is precisely the state the startup sweep exists to clean
    // up and precisely the state a running process should never leave behind.
    private void safelyFail(UUID jobId, String reason) {
        try {
            statusService.markFailed(jobId, reason);
        } catch (RuntimeException e) {
            log.error("Could not record the failure of video job {}", jobId, e);
        }
    }

    private record Attempt(boolean succeeded, int modelCalls, String codePath, String reason) { }
}
