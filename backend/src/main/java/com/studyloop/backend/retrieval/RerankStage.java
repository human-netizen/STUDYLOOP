package com.studyloop.backend.retrieval;

import com.studyloop.backend.config.RetrievalProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Phase 12.1 — the reranking stage, as a step the pipeline can be run with and without.
//
// It owns two decisions RetrievalService should not have to think about: how many candidates to
// fuse before the cross-encoder sees them, and what to do when the provider is unreachable.
//
// The first is why over-retrieval lives here rather than at the call site. Reranking six chunks
// only reorders six chunks; a passage RRF ranked eleventh can never reach the prompt no matter how
// well it answers the question. The stage asks for ~30 so the reranker has something to find, and
// asks for exactly topN when it is switched off so the baseline pipeline is byte-for-byte what
// Phase 11 measured.
//
// The second is fail-open. A rerank outage falls back to the fused order and logs it, because a
// slightly worse answer beats a 502 on a question the corpus could have answered. That is also
// what makes the stage flag honest: with the flag on and the key removed, retrieval still works,
// so the fallback path is reachable rather than theoretical.
@Component
@RequiredArgsConstructor
public class RerankStage {

    private static final Logger log = LoggerFactory.getLogger(RerankStage.class);

    private final RetrievalProperties properties;
    private final RerankClient rerankClient;

    // On only when it is both switched on and able to run. An enabled stage with no API key would
    // otherwise pay the cost of over-retrieval — 30 candidates fused instead of 6 — for a call that
    // is never made.
    public boolean enabled() {
        return properties.stages().rerank() && rerankClient.isConfigured();
    }

    // How deep to fuse before reranking. Never below topN: the caller still needs its k results
    // whether or not anything reorders them.
    public int candidatePool(int topN) {
        return enabled() ? Math.max(topN, properties.rerank().candidates()) : topN;
    }

    // Candidates in fused order, in; the best topN in the cross-encoder's order, out. Each returned
    // chunk carries the relevance score that put it there, which is the signal the confidence gate
    // reads in 12.2 — an unreranked chunk's score is null, and null is how the gate knows to fall
    // back to raw cosine rather than compare against a threshold on a scale nothing produced.
    public List<RetrievedChunk> apply(String query, List<RetrievedChunk> candidates, int topN) {
        if (!enabled() || candidates.size() <= 1) {
            return truncate(candidates, topN);
        }

        List<RerankClient.Ranked> ranked;
        try {
            ranked = rerankClient.rerank(query, candidates.stream().map(RetrievedChunk::content).toList(), topN);
        } catch (RuntimeException e) {
            log.warn("Rerank failed, falling back to the fused order: {}", e.getMessage());
            return truncate(candidates, topN);
        }
        if (ranked.isEmpty()) {
            // The provider answered but ranked nothing. Same fallback as an outage: dropping the
            // candidates on the floor would turn a rerank hiccup into a refusal.
            return truncate(candidates, topN);
        }

        List<RetrievedChunk> reranked = new ArrayList<>(Math.min(ranked.size(), topN));
        for (RerankClient.Ranked entry : ranked) {
            // Indices come from the provider and are used to index an array, so they are checked
            // here rather than trusted. An out-of-range one costs its slot and nothing else.
            if (entry.index() < 0 || entry.index() >= candidates.size()) {
                continue;
            }
            reranked.add(candidates.get(entry.index()).withRerankScore(entry.relevance()));
            if (reranked.size() == topN) {
                break;
            }
        }
        return reranked.isEmpty() ? truncate(candidates, topN) : reranked;
    }

    private static List<RetrievedChunk> truncate(List<RetrievedChunk> chunks, int topN) {
        return chunks.size() <= topN ? chunks : List.copyOf(chunks.subList(0, topN));
    }
}
