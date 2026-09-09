package com.studyloop.backend.course;

// A PATCH that would leave the course in a state a POST could not have created it in → 400.
//
// The one case that matters: a name sent as blank. `name` is `not null` in the schema and
// @NotBlank on the create request, and a partial update is not a licence to route around a
// constraint the create path enforces — a course called "" is one nobody can find in a list.
// Absent is legal (it means "leave the name alone"); blank is not.
public class InvalidCourseUpdateException extends RuntimeException {

    public InvalidCourseUpdateException(String message) {
        super(message);
    }
}
