package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 29.2 — the near-duplicate report's two thresholds and its cost cap.
//
// Both numbers are thresholds on a cosine similarity, and both are provisional until the
// measurement in `NearDuplicateThresholdTest` runs against a real embedder. The stubbed embedder
// the suite runs on keys a vector on the text's content, so identical text lands at 1.0 and
// anything else lands near 0 — enough to prove the mechanism fires and cannot say where between
// those the line belongs.
@ConfigurationProperties(prefix = "studyloop.duplicates")
public record DuplicateProperties(
        // Whether ingestion looks at all. Off makes every column below null and costs nothing;
        // the report is advisory, so nothing downstream changes when it is.
        boolean enabled,

        // How close two passages must be before one counts as already present. High, because the
        // claim is "this paragraph is in the corpus twice", not "these two lectures are about the
        // same subject" — the second is true of every pair of files in a course.
        double chunkFloor,

        // The share of sampled passages that must clear `chunkFloor` before the document is
        // reported at all. This is the number that separates a re-export of one lecture (nearly
        // all of it) from two chapters that share a worked example (a little of it).
        double reportThreshold,

        // How many of the new document's chunks to probe, spread evenly through it rather than
        // taken from the front — the first fifty chunks of a scanned book are its front matter,
        // and front matter matches front matter.
        int sampleChunks
) { }
