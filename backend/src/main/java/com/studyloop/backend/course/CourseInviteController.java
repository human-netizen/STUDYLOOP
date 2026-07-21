package com.studyloop.backend.course;

import com.studyloop.backend.course.dto.CreateInviteRequest;
import com.studyloop.backend.course.dto.InviteResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Managing a course's invites — only its OWNER/INSTRUCTOR get past the service checks.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/invites")
@RequiredArgsConstructor
public class CourseInviteController {

    private final InviteService inviteService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse create(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @Valid @RequestBody CreateInviteRequest request) {
        return inviteService.create(UUID.fromString(authentication.getName()), courseId, request);
    }

    @GetMapping
    public List<InviteResponse> list(Authentication authentication, @PathVariable UUID courseId) {
        return inviteService.listActive(UUID.fromString(authentication.getName()), courseId);
    }

    @DeleteMapping("/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication,
                       @PathVariable UUID courseId,
                       @PathVariable UUID inviteId) {
        inviteService.revoke(UUID.fromString(authentication.getName()), courseId, inviteId);
    }
}
