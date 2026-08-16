package com.studyloop.backend.forum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostAnswerRequest(
        @NotBlank(message = "An answer is required.")
        @Size(max = 8000, message = "That's too long for one reply.")
        String body
) {
}
