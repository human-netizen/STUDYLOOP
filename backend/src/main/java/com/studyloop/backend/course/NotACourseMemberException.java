package com.studyloop.backend.course;

import java.util.UUID;

// Thrown when a real course exists but the caller has no membership in it → 403.
public class NotACourseMemberException extends RuntimeException {

    public NotACourseMemberException(UUID courseId) {
        super("You are not a member of course: " + courseId);
    }
}
