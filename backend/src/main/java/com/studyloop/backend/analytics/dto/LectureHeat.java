package com.studyloop.backend.analytics.dto;

import java.time.Instant;
import java.util.UUID;

// One row of the heatmap: a document and how much of the class's attention it absorbed.
// `share` is this lecture's fraction of all lecture-attributed questions, which is what the bar
// length encodes — raw counts alone make a busy course and a quiet one look identical.
public record LectureHeat(
        UUID documentId,
        String filename,
        int questionCount,
        int distinctAskers,
        double share,
        Instant lastAskedAt
) { }
