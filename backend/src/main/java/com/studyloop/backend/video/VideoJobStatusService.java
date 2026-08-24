package com.studyloop.backend.video;

import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.video.VideoPlanner.PlannedScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Every persisted transition of a video job, each in its own transaction.
//
// The same arrangement DocumentStatusService uses, for the same reason and against the same bug: a
// page polling this job has to see it advance while the render is happening, so a write that stays
// in the persistence context until the whole job commits is a progress bar that jumps from 0 to
// 100 — or, when the job crashes, one that never existed. Separate bean because self-invocation
// does not go through the proxy and the annotations would silently do nothing.
//
// **saveAndFlush rather than dirty checking**, which Phase 20.1 turned from a habit into a rule:
// the reads that matter here are the polling endpoint's, and a status that is only in memory is a
// status the next request does not see.
@Service
@RequiredArgsConstructor
public class VideoJobStatusService {

    // The column is text, but a stack trace in an error field is a stack trace on a student's
    // screen. The reason has to fit in a sentence they can act on or repeat to somebody.
    private static final int MAX_ERROR_LENGTH = 1000;

    private final VideoJobRepository jobRepository;
    private final VideoSceneRepository sceneRepository;
    private final VideoCitationRepository citationRepository;
    private final Clock clock;

    @Transactional
    public void markStage(UUID jobId, VideoJobStatus status, String stage) {
        VideoJob job = require(jobId);
        job.setStatus(status);
        job.setStage(stage);
        if (job.getStartedAt() == null && status != VideoJobStatus.QUEUED) {
            job.setStartedAt(Instant.now(clock));
        }
        jobRepository.saveAndFlush(job);
    }

    // Just the sentence, without moving the state machine. Called once per scene while rendering,
    // which is the only part of a job long enough that a student needs to be told it is still
    // alive.
    @Transactional
    public void markStage(UUID jobId, String stage) {
        VideoJob job = require(jobId);
        job.setStage(stage);
        jobRepository.saveAndFlush(job);
    }

    // The planned scenes, written before any of them is rendered.
    //
    // They are persisted at plan time rather than at the end so the page can show what is about to
    // be made while it is being made, and so a job that dies halfway leaves behind what it
    // intended — which is the difference between a failed job you can diagnose and one you can
    // only re-run.
    @Transactional
    public List<UUID> saveScenes(UUID jobId, List<PlannedScene> scenes) {
        VideoJob job = require(jobId);
        List<UUID> ids = new ArrayList<>(scenes.size());
        for (PlannedScene planned : scenes) {
            VideoScene scene = new VideoScene(job, planned.index(), planned.title(), planned.narration());
            // Every scene starts as a slide and is promoted when Manim actually produces frames.
            // The other way round — start ANIMATED, demote on failure — would mean a crash
            // between the attempt and the demotion leaves a row claiming an animation that does
            // not exist, and the counters this phase reports would be quietly wrong.
            scene.setRenderedAs(SceneRendering.SLIDE);
            VideoScene saved = sceneRepository.saveAndFlush(scene);
            citationRepository.save(saved.getId(), planned.chunkIds());
            ids.add(saved.getId());
        }
        job.setScenesTotal(scenes.size());
        job.setScenesFallback(scenes.size());
        jobRepository.saveAndFlush(job);
        return ids;
    }

    // One scene finished, one way or the other. The job's counters are recomputed from the scene
    // rows rather than incremented, so a retry or a partial re-run cannot drift them.
    @Transactional
    public void markScene(UUID sceneId, SceneRendering rendering, String fallbackReason,
                          int modelCalls, Double durationSeconds, String codePath) {
        VideoScene scene = sceneRepository.findById(sceneId).orElseThrow();
        scene.setRenderedAs(rendering);
        scene.setFallbackReason(truncate(fallbackReason));
        scene.setModelCalls(modelCalls);
        scene.setDurationSeconds(durationSeconds);
        scene.setCodePath(codePath);
        sceneRepository.saveAndFlush(scene);

        VideoJob job = scene.getJob();
        List<VideoScene> all = sceneRepository.findByJobIdOrderBySceneIndex(job.getId());
        int animated = (int) all.stream().filter(VideoScene::animated).count();
        job.setScenesAnimated(animated);
        job.setScenesFallback(all.size() - animated);
        jobRepository.saveAndFlush(job);
    }

    @Transactional
    public void markReady(UUID jobId, String outputPath, String captionsPath, Double durationSeconds) {
        VideoJob job = require(jobId);
        job.setStatus(VideoJobStatus.READY);
        job.setStage(null);
        job.setOutputPath(outputPath);
        job.setCaptionsPath(captionsPath);
        job.setDurationSeconds(durationSeconds);
        job.setError(null);
        job.setFinishedAt(Instant.now(clock));
        jobRepository.saveAndFlush(job);
    }

    @Transactional
    public void markFailed(UUID jobId, String reason) {
        finish(jobId, VideoJobStatus.FAILED, reason);
    }

    // Not a failure. The corpus cannot support the topic, nothing was spent past one embedding,
    // and the message is the gate's own wording so the student reads what chat would have said.
    @Transactional
    public void markRefused(UUID jobId, String reason) {
        finish(jobId, VideoJobStatus.REFUSED, reason);
    }

    private void finish(UUID jobId, VideoJobStatus status, String reason) {
        VideoJob job = require(jobId);
        job.setStatus(status);
        job.setStage(null);
        job.setError(truncate(reason));
        job.setFinishedAt(Instant.now(clock));
        jobRepository.saveAndFlush(job);
    }

    // Read back for the response, with each scene's citations attached. Its own read-only
    // transaction because the runner is not inside one and the caller is usually a poll.
    @Transactional(readOnly = true)
    public List<SceneView> scenes(UUID jobId) {
        var citations = citationRepository.findByJob(jobId);
        return sceneRepository.findByJobIdOrderBySceneIndex(jobId).stream()
                .map(scene -> new SceneView(scene, citations.getOrDefault(scene.getId(), List.of())))
                .toList();
    }

    private VideoJob require(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new VideoJobNotFoundException(jobId));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.length() <= MAX_ERROR_LENGTH ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    public record SceneView(VideoScene scene, List<Citation> citations) { }
}
