package com.studyloop.backend.course;

import java.util.UUID;

// Thrown when a member acts beyond their course role — e.g. a MEMBER trying to issue
// invites, or an INSTRUCTOR trying to grant the INSTRUCTOR role → 403.
public class InsufficientCourseRoleException extends RuntimeException {

    public InsufficientCourseRoleException(UUID courseId) {
        super("Your role in course " + courseId + " doesn't allow this action.");
    }
}
