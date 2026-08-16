package com.studyloop.backend.retrieval;

import com.studyloop.backend.retrieval.dto.SearchDocument;
import com.studyloop.backend.retrieval.dto.SearchHit;
import com.studyloop.backend.retrieval.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Search over a course's materials (Phase 9.3): the same hybrid retrieval the assistant runs,
// presented as passages instead of as an answer.
//
// It is the cheap half of the pipeline. One embedding call, no generation, and no confidence gate
// — a weak match is still shown, because the reader can judge relevance themselves and "here are
// the three places that nearly match" is a useful reply where the assistant's refusal is not.
// That also makes this the escape hatch when the gate refuses: the passages were always there.
//
// Deliberately not logged to question_events. The confusion report counts questions put to the
// assistant; a search box query is a different act, and mixing the two would inflate the volume
// and put words the class never asked into the topic clusters.
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    private final RetrievalService retrievalService;

    // Any course member may search. A blank query returns an empty result rather than an error:
    // the page calls this on every submit, including the one that clears the box.
    public SearchResponse search(UUID actorId, UUID courseId, String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        List<RetrievedChunk> chunks = retrievalService.retrieve(actorId, courseId, trimmed, clampLimit(limit));
        List<String> terms = Snippets.terms(trimmed);

        // Insertion order is the fused RRF order, so a document's position is its best hit's
        // position — the ranking survives the grouping.
        Map<UUID, List<RetrievedChunk>> byDocument = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            byDocument.computeIfAbsent(chunk.documentId(), key -> new ArrayList<>()).add(chunk);
        }

        List<SearchDocument> documents = byDocument.values().stream()
                .map(group -> toDocument(group, terms))
                .toList();
        return new SearchResponse(trimmed, chunks.size(), documents);
    }

    private static SearchDocument toDocument(List<RetrievedChunk> group, List<String> terms) {
        RetrievedChunk head = group.get(0);
        List<SearchHit> hits = group.stream()
                .map(chunk -> new SearchHit(
                        chunk.chunkId(),
                        chunk.pageNumber(),
                        chunk.cosineSimilarity(),
                        chunk.score(),
                        Snippets.of(chunk.content(), terms)))
                .toList();
        return new SearchDocument(head.documentId(), head.filename(), head.source(), hits.size(), hits);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
