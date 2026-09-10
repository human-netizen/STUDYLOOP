package com.studyloop.backend.guide.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// What a student asks for: a topic, in their own words.
//
// One field, as VideoRequest is, and for the same reason: how many sections the guide has is a
// cost and therefore configuration, and whether a section gets written is decided by whether the
// course covers it rather than by a preference. A "number of sections" box would be a promise the
// corpus gets the final say on.
public record StudyGuideRequest(

        @NotBlank(message = "Say what the study guide should be about.")
        @Size(max = 300, message = "Keep the topic under 300 characters.")
        String topic
) { }
