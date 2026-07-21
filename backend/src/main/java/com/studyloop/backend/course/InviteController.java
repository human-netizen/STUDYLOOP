package com.studyloop.backend.course;

import com.studyloop.backend.course.dto.CourseResponse;
import com.studyloop.backend.course.dto.InvitePreviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// The join flow, keyed by the opaque token rather than the course id (the joiner usually
// doesn't know the course id yet). Any authenticated user may preview or accept.
@RestController
@RequestMapping("/api/v1/invites")
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;

    @GetMapping("/{token}")
    public InvitePreviewResponse preview(@PathVariable String token) {
        return inviteService.preview(token);
    }

    @PostMapping("/{token}/accept")
    public CourseResponse accept(Authentication authentication, @PathVariable String token) {
        return inviteService.accept(UUID.fromString(authentication.getName()), token);
    }
}
