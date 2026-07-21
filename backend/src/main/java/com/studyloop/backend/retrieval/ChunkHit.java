package com.studyloop.backend.retrieval;

import java.util.UUID;

// A raw chunk row returned by one search strategy, before fusion. Ranking (position in the
// list) carries the relevance signal RRF uses. cosineSimilarity is the semantic match strength
// (1 = identical direction) for vector hits, or null for lexical-only hits — the confidence gate
// reads it to decide whether the best match is strong enough to answer from.
record ChunkHit(
        UUID id,
        UUID documentId,
        String filename,
        Integer pageNumber,
        String content,
        int tokenCount,
        Double cosineSimilarity
) {
}
