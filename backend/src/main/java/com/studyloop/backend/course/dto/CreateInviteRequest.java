package com.studyloop.backend.course.dto;

import com.studyloop.backend.course.MembershipRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

// All fields optional. Omit email for an open share-link; set it to bind the invite to one
// address. role defaults to MEMBER when null. expiresInHours null means the invite never
// expires. (The requested role is range-checked in the service — OWNER can't be granted.)
public record CreateInviteRequest(
        @Email @Size(max = 255) String email,
        MembershipRole role,
        Long expiresInHours) {
}
