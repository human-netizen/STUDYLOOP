package com.studyloop.backend.analytics.dto;

import java.time.Instant;
import java.util.UUID;

// A question the confidence gate refused. `topSimilarity` is how close the best chunk in the
// course got: a 0.24 is a near miss worth a clarifying paragraph, while a null or a very low
// score usually means the question was off-topic and the gate did its job.
//
// The asker is not here, and that is the design. An instructor seeing *what* the class cannot
// answer is analytics; an instructor seeing *who* asked it is a different product.
//
// `questionEventId` and `threadId` are what make the list actionable rather than merely sobering:
// the id identifies the question so a forum thread can be opened against it, and the thread id —
// null until someone does — turns the row into a link to the discussion it became (Phase 9.2).
public record UngroundedQuestion(
        String question,
        Double topSimilarity,
        Instant askedAt,
        UUID questionEventId,
        UUID threadId,
        // Whether the student answered it from general knowledge instead (Phase 20.2). Two rows
        // with the same low similarity read very differently once this is on one of them: one is
        // a question nobody missed, the other is a gap somebody worked around.
        boolean escalatedToGeneral
) { }
