package com.studyloop.backend.video;

import com.studyloop.backend.video.VideoService.VideoFile;
import com.studyloop.backend.video.dto.VideoJobResponse;
import com.studyloop.backend.video.dto.VideoLibraryResponse;
import com.studyloop.backend.video.dto.VideoRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    // Ask for a video. 202 with the job handle, exactly as an upload does — the work takes
    // minutes, so the only honest synchronous answer is "accepted, here is what to poll".
    @PostMapping
    public ResponseEntity<VideoJobResponse> request(Authentication authentication,
                                                    @PathVariable UUID courseId,
                                                    @Valid @RequestBody VideoRequest request) {
        VideoJobResponse job = videoService.request(
                UUID.fromString(authentication.getName()), courseId, request.topic());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    // The member's own jobs, plus whether this installation can make videos at all. One call, so
    // the page cannot draw a button from a flag it fetched at a different time than the jobs.
    @GetMapping
    public VideoLibraryResponse library(Authentication authentication, @PathVariable UUID courseId) {
        return videoService.library(UUID.fromString(authentication.getName()), courseId);
    }

    @GetMapping("/{jobId}")
    public VideoJobResponse getOne(Authentication authentication,
                                   @PathVariable UUID courseId,
                                   @PathVariable UUID jobId) {
        return videoService.get(UUID.fromString(authentication.getName()), courseId, jobId);
    }

    // The bytes, through the same authorization as the job. Inline so the browser plays it, and
    // fetched with the bearer token rather than linked, because a video can be grounded on the
    // requester's private notes and a static URL would be a link anyone could follow.
    @GetMapping("/{jobId}/file")
    public ResponseEntity<byte[]> file(Authentication authentication,
                                       @PathVariable UUID courseId,
                                       @PathVariable UUID jobId) {
        return stream(videoService.file(UUID.fromString(authentication.getName()), courseId, jobId));
    }

    // The caption track, on the same terms. 404 when the render produced no word timings, which
    // the player treats as a video without captions rather than as an error.
    @GetMapping("/{jobId}/captions")
    public ResponseEntity<byte[]> captions(Authentication authentication,
                                           @PathVariable UUID courseId,
                                           @PathVariable UUID jobId) {
        return stream(videoService.captions(UUID.fromString(authentication.getName()), courseId, jobId));
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> delete(Authentication authentication,
                                       @PathVariable UUID courseId,
                                       @PathVariable UUID jobId) {
        videoService.delete(UUID.fromString(authentication.getName()), courseId, jobId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<byte[]> stream(VideoFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.filename()).build().toString())
                .body(file.bytes());
    }
}
