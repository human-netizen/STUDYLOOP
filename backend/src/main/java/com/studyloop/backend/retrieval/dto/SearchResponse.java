package com.studyloop.backend.retrieval.dto;

import java.util.List;

// The search result set. `documents` is ordered by each document's best hit, which is the fused
// RRF order the chunks came back in — so the ranking the retriever produced is the ranking the
// reader sees.
public record SearchResponse(String query, int hitCount, List<SearchDocument> documents,
                             // Phase 23.2 — the narrowing this search was subjected to because the
                             // query named a week or a kind of material the course actually has,
                             // or null. The search page has the same claim on it that the chat
                             // page does: results drawn from three of fourteen documents look
                             // exactly like results drawn from fourteen.
                             String scopeNote) {

    // The unnarrowed shape, which is what every search was before this phase and what almost every
    // search still is.
    public SearchResponse(String query, int hitCount, List<SearchDocument> documents) {
        this(query, hitCount, documents, null);
    }
}
