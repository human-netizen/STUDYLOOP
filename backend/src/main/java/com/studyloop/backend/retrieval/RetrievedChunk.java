package com.studyloop.backend.retrieval;

import java.util.UUID;

// A chunk that hybrid retrieval surfaced for a query, plus its fused RRF score (higher = more
// relevant). Carries enough to cite the source (document + page) and to feed the chunk text
// into a RAG prompt later (Phase 5.2), so callers never need a second fetch.
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String filename,
        // 1-based page the chunk starts on; null if the source had no page information.
        Integer pageNumber,
        String content,
        int tokenCount,
        double score
) {

    static RetrievedChunk of(ChunkHit hit, double score) {
        return new RetrievedChunk(
                hit.id(), hit.documentId(), hit.filename(), hit.pageNumber(),
                hit.content(), hit.tokenCount(), score);
    }
}
