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
        int lexicalHitCount
) {
}
