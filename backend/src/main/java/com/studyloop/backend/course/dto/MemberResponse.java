package com.studyloop.backend.course.dto;

import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRole;

import java.time.Instant;
import java.util.UUID;

// One person in a course (Phase 27.4). The id is the handle for removing them; the name and the
// email are what the row shows, because "remove 8f3a-…" is not a decision anybody can make.
//
// The email is here and nowhere else in this package. It is the field that tells two people with
// the same display name apart, and a course's own members already know each other's — invites are
// sent to it. It is not exposed on any response a non-member can reach.
public record MemberResponse(
        UUID userId,
        String displayName,
        String email,
        MembershipRole role,
        Instant joinedAt
) {

    public static MemberResponse from(Membership membership) {
        return new MemberResponse(
                membership.getUser().getId(),
                membership.getUser().getDisplayName(),
                membership.getUser().getEmail(),
                membership.getRole(),
                membership.getCreatedAt());
    }
}
