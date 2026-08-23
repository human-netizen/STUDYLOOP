package com.studyloop.backend.chat.dto;

import java.time.Instant;

// "You have asked about this before" — the one-line context header a chat turn carries when the
// student has already asked this course something that means the same thing (Phase 20.3).
//
// What is *not* in this record is the point of it. There is no previous answer here, and none is
// loaded: the cheap, safe half of a cross-chat memory is knowing that a question recurs, and the
// expensive, dangerous half is re-serving what was said last time as if it were established. The
// numbers come from question_events, which stores what students asked and whether the corpus
// could ground it — facts, not model output.
public record AskedBefore(
        // How many earlier questions clustered with this one, not counting the current turn.
        int times,
        Instant lastAskedAt,
        // The student's own wording last time. Shown because "you asked this before" is a claim a
        // reader will want to check, and because their earlier phrasing is often the better one.
        String lastQuestion
) { }
