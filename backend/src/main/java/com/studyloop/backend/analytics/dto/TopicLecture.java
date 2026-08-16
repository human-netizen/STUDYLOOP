package com.studyloop.backend.analytics.dto;

import java.util.UUID;

// A lecture one topic's questions were grounded on, with how many of them landed there.
public record TopicLecture(UUID documentId, String filename, int questionCount) { }
