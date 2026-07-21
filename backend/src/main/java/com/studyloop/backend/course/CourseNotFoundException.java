package com.studyloop.backend.course;

import java.util.UUID;

public class CourseNotFoundException extends RuntimeException {

    public CourseNotFoundException(UUID id) {
        super("No course with id: " + id);
    }
}
