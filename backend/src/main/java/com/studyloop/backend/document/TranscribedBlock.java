package com.studyloop.backend.document;

// One block a vision model read off a photographed page, and how sure it says it was (Phase 16.3).
//
// **Why the confidence is kept rather than thresholded and thrown away.** Handwriting recognition
// is wrong often enough that "we dropped the parts we could not read" is only half an answer — the
// other half is showing the student *which* parts, so they can retype the two lines the model gave
// up on instead of discovering months later that their notes on quicksort stop mid-recurrence. A
// dropped block that nobody is told about is indistinguishable from a block that was never there.
//
// `indexed` is the decision, made once at ingest against the configured threshold and then stored,
// for the same reason `documents.vision_pages` is stored: recomputing it later from the threshold
// would answer what the current configuration would do, not what this note's corpus was built with.
public record TranscribedBlock(int ordinal, String content, double confidence, boolean indexed) { }
