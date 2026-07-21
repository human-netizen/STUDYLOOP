package com.studyloop.backend.course.dto;

import com.studyloop.backend.course.CourseInvite;
import com.studyloop.backend.course.MembershipRole;

import java.time.Instant;
import java.util.UUID;

// Returned to course managers after creating or when listing invites. acceptPath is the
// server route the holder POSTs to; the frontend wraps it into a shareable URL.
public record InviteResponse(
        UUID id,
        UUID courseId,
        String token,
        String email,
        MembershipRole role,
        Instant expiresAt,
        Instant createdAt,
        String acceptPath
) {

    // courseId is passed in (from the request path) so this never touches the lazy course
    // association.
    public static InviteResponse from(CourseInvite invite, UUID courseId) {
        return new InviteResponse(
                invite.getId(),
                courseId,
                invite.getToken(),
                invite.getEmail(),
                invite.getRole(),
                invite.getExpiresAt(),
                invite.getCreatedAt(),
                "/api/v1/invites/" + invite.getToken() + "/accept"
        );
    }
}
