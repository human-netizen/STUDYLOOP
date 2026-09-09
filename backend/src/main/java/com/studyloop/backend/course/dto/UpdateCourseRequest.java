package com.studyloop.backend.course.dto;

import jakarta.validation.constraints.Size;

// The first PATCH body in this codebase (Phase 27.4).
//
// **Every field is nullable, and null means "leave this one alone".** That is what makes it a
// PATCH rather than a PUT: a client that wants to rename a course sends the name and nothing
// else, and does not have to have read — or be responsible for round-tripping — a description it
// never showed. The cost is that "clear the description" cannot be expressed as null, which is
// why blank is accepted and treated as clearing it.
//
// `name` has no @NotBlank, unlike CreateCourseRequest: absent is legal here and blank is not, and
// those are two different rejections. The service makes the second one.
public record UpdateCourseRequest(

        @Size(max = 100)
        String name,

        @Size(max = 1000)
        String description
) {

    public boolean isEmpty() {
        return name == null && description == null;
    }
}
