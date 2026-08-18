package com.studyloop.backend.retrieval;

import java.util.List;
import java.util.OptionalDouble;

// The outcome of one hybrid retrieval: the fused top-k chunks plus the two raw signals a
// confidence gate needs. topVectorSimilarity is the best semantic match strength (empty when no
// embedding provider is configured, so no vector search ran); lexicalHitCount is how many chunks
// shared vocabulary with the query. A weak semantic match with zero lexical overlap is the mark
// of an off-topic question — the chat layer refuses those rather than letting the model guess.
public record RetrievalResult(
        List<RetrievedChunk> chunks,
        OptionalDouble topVectorSimilarity,
        int lexicalHitCount,
        // The best chunk's cross-encoder relevance, empty when the rerank stage did not run.
        // Present, it is the better of the two confidence signals and the gate reads it instead:
        // it is calibrated, so a fixed threshold on it means the same thing for every question,
        // which is exactly what the pair above is not (Phase 12.2).
        OptionalDouble topRerankScore,
        // The vector the semantic half actually searched with — the caller's, if it passed one,
        // otherwise the one embedded here. Null when no embedding provider is configured, so no
        // vector search ran at all. Handed back so a caller that needs the question's embedding
        // for something else (Phase 9.1 logs it for clustering) reuses this one instead of paying
        // the provider to embed the same string twice.
        float[] queryVector
) {
}
