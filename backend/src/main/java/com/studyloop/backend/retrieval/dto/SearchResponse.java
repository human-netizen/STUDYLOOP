package com.studyloop.backend.retrieval.dto;

import java.util.List;

// The search result set. `documents` is ordered by each document's best hit, which is the fused
// RRF order the chunks came back in — so the ranking the retriever produced is the ranking the
// reader sees.
public record SearchResponse(String query, int hitCount, List<SearchDocument> documents) {
}
