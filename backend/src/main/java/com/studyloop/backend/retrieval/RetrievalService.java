package com.studyloop.backend.retrieval;

import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.VectorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
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
//
// That position-only view is also RRF's ceiling, and Phase 12.1's rerank stage is what sits on top
// of it: fuse deeper than the caller asked for, then let a cross-encoder that has read the query
// pick the k that come back.
//
// Phase 17.3 added a third list — page images, embedded into the same space as the text — and
// changed nothing else here, which was the point. `fuse` never knew how many rankings it was given,
// so a new retriever is a new element in a list literal. What it does mean is that a page can now
// reach the prompt on evidence no word of it carries, and the cross-encoder above still scores it
// on its words: a figure page promoted by its picture and demoted by its caption is the interaction
// this phase has to be measured with reranking on, not just with it off.
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
    private final RerankStage rerankStage;
    private final VisualStage visualStage;

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
        return search(actorId, courseId, query, null, limit);
    }

    // The same search, reusing a query embedding the caller already has. Chat embeds a question
    // once — to probe the semantic cache — and hands the vector down here, so a cache miss costs
    // one embedding call instead of embedding the identical string twice. Pass null to embed here.
    @Transactional(readOnly = true)
    public RetrievalResult search(UUID actorId, UUID courseId, String query, float[] queryVector, int limit) {
        courseAccess.requireMember(actorId, courseId);

        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            // Nothing was embedded, so there is no vector to hand back — not even the caller's,
            // which was computed for a query we are refusing to run.
            return new RetrievalResult(List.of(), OptionalDouble.empty(), 0, OptionalDouble.empty(), null);
        }
        int topN = clampLimit(limit);

        // Semantic half — only when an embedding provider is configured. If not, retrieval
        // degrades gracefully to full-text alone rather than failing.
        float[] vector = queryVector != null ? queryVector
                : embeddingClient.isConfigured() ? embeddingClient.embedQuery(trimmed) : null;
        List<ChunkHit> vectorHits = vector != null
                ? searchRepository.vectorSearch(
                        courseId, actorId, VectorSupport.toLiteral(vector), CANDIDATES_PER_SOURCE)
                : List.of();

        // Lexical half.
        // The actor is passed to both halves, not just checked above: membership decides whether
        // this course may be searched at all, and Phase 16.3's visibility decides which of its
        // documents this member may be answered from. A private note is retrievable for the person
        // who photographed it and invisible to everyone else, in one clause inside the SQL.
        List<ChunkHit> textHits =
                searchRepository.fullTextSearch(courseId, actorId, trimmed, CANDIDATES_PER_SOURCE);

        // Vector hits come back best-first, so the head is the strongest semantic match. Empty
        // when no embedding provider ran, which the gate reads as "no semantic signal".
        //
        // Read from the *text* half only, deliberately, and Phase 17 did not change that. A
        // text-to-image cosine and a text-to-text cosine are not the same measurement even in one
        // vector space — image embeddings sit apart from text embeddings in it, so cross-modal
        // scores run systematically lower — and feeding both into one threshold would recalibrate
        // the confidence gate by adding a retriever. That is also why the third list is fused by
        // rank rather than by score: RRF never compares the two scales.
        OptionalDouble topSimilarity = vectorHits.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(vectorHits.get(0).cosineSimilarity());

        // Third list (17.3): pages whose *picture* is near the query, searched with the vector the
        // dense half already embedded. Empty when the stage is off, which is what makes the A/B a
        // property change — `fuse` is list-count agnostic, so turning it on is a caller change and
        // nothing more.
        List<ChunkHit> visualHits = visualStage.search(courseId, actorId, vector);

        // Fusion depth is the rerank stage's call: topN when it is off, ~30 when it is on, so the
        // cross-encoder has candidates to promote rather than six it can only shuffle.
        List<RetrievedChunk> fused =
                fuse(List.of(vectorHits, textHits, visualHits), rerankStage.candidatePool(topN));
        List<RetrievedChunk> ranked = rerankStage.apply(trimmed, fused, topN);

        // The head's relevance, empty when nothing reranked. Read from the returned list rather
        // than tracked separately, so the number the gate sees is the one that actually led the
        // results — including when the stage failed open and there is no relevance at all.
        Double topRelevance = ranked.isEmpty() ? null : ranked.get(0).rerankScore();
        return new RetrievalResult(ranked, topSimilarity, textHits.size(),
                topRelevance == null ? OptionalDouble.empty() : OptionalDouble.of(topRelevance),
                vector);
    }

    // Reciprocal Rank Fusion: a chunk at 0-based rank r in a list contributes 1/(K + r + 1);
    // scores sum across lists, so chunks that both strategies rank highly rise to the top.
    //
    // **Ties are broken by which list found the chunk first, and that had to be made explicit in
    // Phase 17.** Two lists' leading chunks score identically — 1/(K+1) each — so the moment there
    // were three retrievers a tie at the top became the ordinary case rather than a curiosity. The
    // old sort read a HashMap's entry set, which orders by hash: identical inputs could produce
    // different top-k on different runs, which is a bad property for a pipeline and a disqualifying
    // one for a harness whose entire job is to compare two runs. Iterating `byId` instead makes the
    // order (list, then rank within list), and a stable sort keeps it.
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
        return byId.values().stream()
                .sorted(Comparator.comparingDouble((ChunkHit hit) -> scores.get(hit.id())).reversed())
                .limit(topN)
                .map(hit -> RetrievedChunk.of(hit, scores.get(hit.id())))
                .toList();
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
