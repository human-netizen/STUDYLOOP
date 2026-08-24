package com.studyloop.backend.video;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.config.VideoProperties;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.video.dto.VideoJobResponse;
import com.studyloop.backend.video.dto.VideoLibraryResponse;
import com.studyloop.backend.video.dto.VideoSceneResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The request side of Phase 21: accept a job, report on it, hand back the file.
//
// **Everything here is scoped to the requester, not to the course**, and that is 20.1's rule
// applied to a new artifact rather than rediscovered later. A video can be grounded on the asking
// member's own OWNER-visibility notes — which is what makes "make me a video from the notes I
// photographed" work at all — so the finished file inherits their visibility. A course-wide video
// would need a second grounding pass over course-visible material only, plus a promotion step with
// a guard on it, and that is a phase rather than a parameter.
@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoJobRepository jobRepository;
    private final VideoSceneRepository sceneRepository;
    private final VideoCitationRepository citationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final VideoWorker worker;
    private final VideoStorageService storage;
    private final VideoProperties properties;
    private final CourseAccess courseAccess;
    private final UserRepository userRepository;
    private final Clock clock;

    // Accepts the job and returns immediately; the render happens on the video executor.
    //
    // The three refusals that happen *here* rather than in the runner are the three that cost
    // nothing to detect: the feature is off, the renderer is down, or the member has spent their
    // day's allowance. Everything else — including the corpus not covering the topic — is a job
    // row, because a student who asked for a video is owed a record of what happened to the ask.
    @Transactional
    public VideoJobResponse request(UUID actorId, UUID courseId, String topic) {
        requireEnabled();
        Membership membership = courseAccess.requireMember(actorId, courseId);
        requireWorker();
        requireDailyAllowance(actorId);

        User requester = userRepository.findById(actorId).orElseThrow();
        // ENGLISH at creation; the planner decides the real one from the material it retrieves and
        // the runner never writes it back to a job that was refused. A column that said BANGLA for
        // a job with no narration would be recording a guess as a fact.
        VideoJob job = new VideoJob(membership.getCourseSpace(), requester, topic.strip(), Language.ENGLISH);
        VideoJob saved = jobRepository.saveAndFlush(job);

        // An event rather than a direct call, so the render starts after this transaction has
        // committed. Starting it here would race the insert: the executor is free, it reads the
        // job by id, and the row is not visible yet.
        eventPublisher.publishEvent(new VideoJobQueuedEvent(saved.getId(), actorId));
        return toResponse(saved, List.of());
    }

    @Transactional(readOnly = true)
    public VideoLibraryResponse library(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        if (!properties.enabled()) {
            // No worker probe when the feature is off — a health check against a service this
            // installation does not have is a second of latency on every page load to learn
            // something the flag already said.
            return new VideoLibraryResponse(false, false, properties.dailyCapPerUser(), 0, List.of());
        }
        List<VideoJobResponse> jobs = jobRepository
                .findByCourseSpaceIdAndRequestedByIdOrderByCreatedAtDesc(courseId, actorId).stream()
                .map(job -> toResponse(job, List.of()))
                .toList();
        return new VideoLibraryResponse(
                true,
                worker.health().ready(),
                properties.dailyCapPerUser(),
                (int) usedToday(actorId),
                jobs);
    }

    // One job with its scenes. This is what the page polls, so it is one query for the job, one
    // for the scenes and one for every scene's citations — three, regardless of scene count.
    @Transactional(readOnly = true)
    public VideoJobResponse get(UUID actorId, UUID courseId, UUID jobId) {
        courseAccess.requireMember(actorId, courseId);
        VideoJob job = require(actorId, courseId, jobId);
        return toResponse(job, scenes(job.getId()));
    }

    // The bytes, behind the same membership check and the same ownership check as the job.
    //
    // **Never a static path.** Serving these from a directory the web server exposes would make a
    // video grounded on somebody's private notes readable by anyone who learned its URL, and the
    // URL is a UUID in a page they were shown. The document viewer took the same decision in Phase
    // 6.2 for the same reason.
    @Transactional(readOnly = true)
    public VideoFile file(UUID actorId, UUID courseId, UUID jobId) {
        courseAccess.requireMember(actorId, courseId);
        VideoJob job = require(actorId, courseId, jobId);
        if (job.getStatus() != VideoJobStatus.READY || job.getOutputPath() == null) {
            throw new VideoJobNotFoundException(jobId);
        }
        return new VideoFile(storage.read(job.getOutputPath()), "video/mp4",
                "studyloop-" + jobId + ".mp4");
    }

    @Transactional(readOnly = true)
    public VideoFile captions(UUID actorId, UUID courseId, UUID jobId) {
        courseAccess.requireMember(actorId, courseId);
        VideoJob job = require(actorId, courseId, jobId);
        if (job.getCaptionsPath() == null || !storage.exists(job.getCaptionsPath())) {
            throw new VideoJobNotFoundException(jobId);
        }
        return new VideoFile(storage.read(job.getCaptionsPath()), "text/vtt",
                "studyloop-" + jobId + ".vtt");
    }

    // Removes the row and everything on disk behind it. The scene rows and citation links go with
    // it by cascade; the files have to be asked for.
    @Transactional
    public void delete(UUID actorId, UUID courseId, UUID jobId) {
        courseAccess.requireMember(actorId, courseId);
        VideoJob job = require(actorId, courseId, jobId);
        if (!job.getStatus().isTerminal()) {
            // A running job's files are being written as we speak. Deleting the row would leave
            // the runner writing a status onto a row that no longer exists, which fails as a
            // constraint violation somewhere unhelpful.
            throw new VideoJobRunningException();
        }
        sceneRepository.deleteByJobId(jobId);
        jobRepository.delete(job);
        storage.deleteJob(courseId, jobId);
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new VideoDisabledException(
                    "Video generation is not switched on for this installation.");
        }
    }

    private void requireWorker() {
        VideoWorker.Health health = worker.health();
        if (!health.ready()) {
            throw new VideoDisabledException("The video renderer is not available. " + health.detail());
        }
    }

    private void requireDailyAllowance(UUID actorId) {
        if (usedToday(actorId) >= properties.dailyCapPerUser()) {
            throw new VideoDailyCapExceededException(properties.dailyCapPerUser());
        }
    }

    // A rolling 24 hours rather than a calendar day, because a calendar day needs a timezone and
    // the only honest one to pick would be the server's.
    private long usedToday(UUID actorId) {
        return jobRepository.countRecentByRequester(actorId, Instant.now(clock).minus(Duration.ofDays(1)));
    }

    private VideoJob require(UUID actorId, UUID courseId, UUID jobId) {
        return jobRepository.findByIdAndCourseSpaceIdAndRequestedById(jobId, courseId, actorId)
                .orElseThrow(() -> new VideoJobNotFoundException(jobId));
    }

    private List<VideoSceneResponse> scenes(UUID jobId) {
        var citations = citationRepository.findByJob(jobId);
        return sceneRepository.findByJobIdOrderBySceneIndex(jobId).stream()
                .map(scene -> new VideoSceneResponse(
                        scene.getSceneIndex(),
                        scene.getTitle(),
                        scene.getNarration(),
                        scene.getRenderedAs(),
                        scene.getFallbackReason(),
                        scene.getDurationSeconds(),
                        citations.getOrDefault(scene.getId(), List.of())))
                .toList();
    }

    private VideoJobResponse toResponse(VideoJob job, List<VideoSceneResponse> scenes) {
        return new VideoJobResponse(
                job.getId(),
                job.getCourseSpace().getId(),
                job.getTopic(),
                job.getLanguage(),
                job.getStatus(),
                job.getStage(),
                job.getScenesTotal(),
                job.getScenesAnimated(),
                job.getScenesFallback(),
                job.getDurationSeconds(),
                job.getCaptionsPath() != null,
                job.getError(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                scenes);
    }

    // Kept out of the controller so the storage read and the content type stay next to the check
    // that authorised them.
    public record VideoFile(byte[] bytes, String contentType, String filename) { }
}
