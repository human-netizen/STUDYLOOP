package com.studyloop.backend.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Hybrid search over a course's ingested chunks. This is the retrieval core the RAG chat
// (Phase 5.2) will call internally; exposing it directly also lets us eyeball relevance and
// measure retrieval hit-rate against a golden set (Phase 5.4).
@RestController
@RequestMapping("/api/v1/courses/{courseId}/retrieve")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    @GetMapping
    public List<RetrievedChunk> retrieve(Authentication authentication,
                                         @PathVariable UUID courseId,
                                         @RequestParam("q") String query,
                                         @RequestParam(value = "k", defaultValue = "6") int k) {
        return retrievalService.retrieve(UUID.fromString(authentication.getName()), courseId, query, k);
    }
}
