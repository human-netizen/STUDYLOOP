package com.studyloop.backend.course.dto;

import com.studyloop.backend.course.MembershipRole;

import java.util.UUID;

// What a would-be joiner sees before accepting, so the UI can show "Join <course> as MEMBER?".
// Deliberately omits the invite's target email — it only flags whether one is required.
public record InvitePreviewResponse(
        UUID courseId,
        String courseName,
        MembershipRole role,
        boolean requiresMatchingEmail
) {
}
