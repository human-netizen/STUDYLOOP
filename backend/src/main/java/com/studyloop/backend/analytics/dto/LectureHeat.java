package com.studyloop.backend.analytics.dto;

import java.time.Instant;
import java.util.UUID;

// One row of the heatmap: a document and how much of the class's attention it absorbed.
// `share` is this lecture's fraction of all lecture-attributed questions, which is what the bar
// length encodes — raw counts alone make a busy course and a quiet one look identical.
public record LectureHeat(
        // Null for a lecture that has been deleted (Phase 27.3): the questions that landed on it
        // are still counted, and still take their share of the bar, but there is nothing left to
        // open. The client renders those rows without a link rather than hiding them, because
        // hiding them is what made the per-lecture totals stop reconciling with the course total.
        UUID documentId,
        String filename,
        int questionCount,
        int distinctAskers,
        double share,
        Instant lastAskedAt
) { }
