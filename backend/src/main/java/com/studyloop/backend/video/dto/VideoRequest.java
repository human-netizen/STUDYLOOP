package com.studyloop.backend.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// What a student asks for: a topic, in their own words.
//
// One field, and the absence of the others is the design. No length, no style, no "make it
// animated" — length is capped by configuration because it is a cost, and how much of it gets
// animated is decided by what the sandbox lets through rather than by a preference. A checkbox
// asking for animation would be a promise the pipeline cannot keep.
public record VideoRequest(

        @NotBlank(message = "Say what the video should be about.")
        @Size(max = 300, message = "Keep the topic under 300 characters.")
        String topic
) { }
