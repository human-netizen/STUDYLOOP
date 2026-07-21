package com.studyloop.backend.retrieval;

import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.VectorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

// Hybrid retrieval: run a semantic (vector) search and a lexical (full-text) search over a
// course's chunks, then blend the two rankings with Reciprocal Rank Fusion. RRF needs no score
// calibration between the two very different scales — it only looks at each chunk's *position*
// in each list — which makes it a robust default for combining dense and sparse retrieval.
@Service
@RequiredArgsConstructor
public class RetrievalService {

    // RRF constant. 60 is the value from the original Cormack et al. paper and the common
    // default; larger flattens the contribution of top ranks, smaller sharpens it.
    private static final int RRF_K = 60;
    // How many candidates to pull from each strategy before fusing (plan: top-20 per source).
    private static final int CANDIDATES_PER_SOURCE = 20;
    private static final int DEFAULT_LIMIT = 6;
    private static final int MAX_LIMIT = 20;

    private final CourseAccess courseAccess;
    private final EmbeddingClient embeddingClient;
    private final ChunkSearchRepository searchRepository;

    // Any course member may search the course's materials. Returns the fused top-`limit`
    // chunks, best-first; an empty/blank query or a course with no matching chunks yields [].
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(UUID actorId, UUID courseId, String query, int limit) {
        return search(actorId, courseId, query, limit).chunks();
    }

    // Like retrieve, but also reports the raw confidence signals (best cosine similarity and
    // lexical hit count) the chat layer's gate needs. Both searches run once; the fused chunks
    // and the signals come from the same candidate lists, so there's no extra query.
    @Transactional(readOnly = true)
    public RetrievalResult search(UUID actorId, UUID courseId, String query, int limit) {
        courseAccess.requireMember(actorId, courseId);

        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new RetrievalResult(List.of(), OptionalDouble.empty(), 0);
        }
        int topN = clampLimit(limit);

        // Semantic half — only when an embedding provider is configured. If not, retrieval
        // degrades gracefully to full-text alone rather than failing.
        List<ChunkHit> vectorHits = embeddingClient.isConfigured()
                ? searchRepository.vectorSearch(
                        courseId, VectorSupport.toLiteral(embeddingClient.embedQuery(trimmed)),
                        CANDIDATES_PER_SOURCE)
                : List.of();

        // Lexical half.
        List<ChunkHit> textHits = searchRepository.fullTextSearch(courseId, trimmed, CANDIDATES_PER_SOURCE);

        // Vector hits come back best-first, so the head is the strongest semantic match. Empty
        // when no embedding provider ran, which the gate reads as "no semantic signal".
        OptionalDouble topSimilarity = vectorHits.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(vectorHits.get(0).cosineSimilarity());

        List<RetrievedChunk> fused = fuse(List.of(vectorHits, textHits), topN);
        return new RetrievalResult(fused, topSimilarity, textHits.size());
    }

    // Reciprocal Rank Fusion: a chunk at 0-based rank r in a list contributes 1/(K + r + 1);
    // scores sum across lists, so chunks that both strategies rank highly rise to the top.
    private static List<RetrievedChunk> fuse(List<List<ChunkHit>> rankings, int topN) {
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, ChunkHit> byId = new LinkedHashMap<>();
        for (List<ChunkHit> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                ChunkHit hit = ranking.get(rank);
                scores.merge(hit.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
                byId.putIfAbsent(hit.id(), hit);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topN)
                .map(entry -> RetrievedChunk.of(byId.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
