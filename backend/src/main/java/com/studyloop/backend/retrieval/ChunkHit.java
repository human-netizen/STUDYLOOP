package com.studyloop.backend.retrieval;

import java.util.UUID;

// A raw chunk row returned by one search strategy, before fusion. Ranking (position in the
// list) carries the relevance signal RRF uses, so no per-strategy score is kept here.
record ChunkHit(
        UUID id,
        UUID documentId,
        String filename,
        Integer pageNumber,
        String content,
        int tokenCount
) {
}
