package com.studyloop.backend.retrieval.dto;

import java.util.List;
import java.util.UUID;

// One passage that matched. `snippet` is a window of the chunk around the first matching word,
// not the whole chunk — a chunk is several hundred tokens and nobody reads that in a result list.
//
// `similarity` is the raw cosine of the chunk against the query, or null when the chunk was found
// lexically only. It is the same number the confidence gate reads, so a searcher sees exactly what
// the assistant saw.
public record SearchHit(
        UUID chunkId,
        // 1-based page the chunk starts on; null for sources that have no pages (forum answers).
        Integer pageNumber,
        Double similarity,
        double score,
        List<SnippetPart> snippet
) {
}
