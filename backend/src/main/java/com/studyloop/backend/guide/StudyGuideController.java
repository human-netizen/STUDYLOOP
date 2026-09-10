package com.studyloop.backend.guide;

import com.studyloop.backend.guide.dto.StudyGuideLibraryResponse;
import com.studyloop.backend.guide.dto.StudyGuideRequest;
import com.studyloop.backend.guide.dto.StudyGuideResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/courses/{courseId}/guides")
@RequiredArgsConstructor
public class StudyGuideController {

    private final StudyGuideService guideService;

    // Ask for a guide. 202 with the row to poll, exactly as an upload and a video do — the work is
    // tens of seconds of model calls, so the only honest synchronous answer is "accepted".
    @PostMapping
    public ResponseEntity<StudyGuideResponse> request(Authentication authentication,
                                                      @PathVariable UUID courseId,
                                                      @Valid @RequestBody StudyGuideRequest request) {
        StudyGuideResponse guide = guideService.request(
                UUID.fromString(authentication.getName()), courseId, request.topic());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(guide);
    }

    @GetMapping
    public StudyGuideLibraryResponse library(Authentication authentication, @PathVariable UUID courseId) {
        return guideService.library(UUID.fromString(authentication.getName()), courseId);
    }

    @GetMapping("/{guideId}")
    public StudyGuideResponse getOne(Authentication authentication,
                                     @PathVariable UUID courseId,
                                     @PathVariable UUID guideId) {
        return guideService.get(UUID.fromString(authentication.getName()), courseId, guideId);
    }

    @DeleteMapping("/{guideId}")
    public ResponseEntity<Void> delete(Authentication authentication,
                                       @PathVariable UUID courseId,
                                       @PathVariable UUID guideId) {
        guideService.delete(UUID.fromString(authentication.getName()), courseId, guideId);
        return ResponseEntity.noContent().build();
    }
}
