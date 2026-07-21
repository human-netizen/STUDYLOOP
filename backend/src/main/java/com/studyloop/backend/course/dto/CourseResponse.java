package com.studyloop.backend.course.dto;

import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.MembershipRole;

import java.time.Instant;
import java.util.UUID;

public record CourseResponse(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        // The calling user's role in this course — lets the UI show/hide actions.
        MembershipRole myRole,
        Instant createdAt
) {

    public static CourseResponse from(CourseSpace course, MembershipRole myRole) {
        return new CourseResponse(
                course.getId(),
                course.getName(),
                course.getDescription(),
                course.getOwner().getId(),
                myRole,
                course.getCreatedAt()
        );
    }
}
