package com.studyloop.backend.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCourseRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 1000)
        String description
) {
}
