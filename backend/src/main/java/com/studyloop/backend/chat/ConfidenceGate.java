package com.studyloop.backend.chat;

import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.retrieval.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Whether a retrieval is strong enough to answer from, or whether the honest reply is "not in your
// materials". Phase 5.3's rule, lifted out of ChatService.
//
// It moved here for two reasons. The eval harness has to measure the gate production actually
// uses — a copy of the rule in test code would drift, and the refusal rate it reports would then
// describe a policy nobody runs. And Phase 12.2 changes the signal it reads; a policy with one
// caller and one seam is a change to one class rather than surgery inside the orchestrator.
//
// Phase 12.2 is that change. There are now two rules, and which one applies is decided by what
// retrieval produced rather than by a setting:
//
//   reranked      compare the cross-encoder's relevance against min-relevance, and nothing else
//   not reranked  the Phase 5.3 rule: weak cosine AND no lexical hit
//
// The split is not a migration in progress; it is the honest reading of two different scales. A
// raw cosine has no fixed meaning — 0.44 is a good match in one corpus and noise in another —
// which is why the old rule needs a hand-tuned number *and* a lexical escape hatch to survive its
// own false positives. Phase 11.1 measured exactly what that costs: on sixty golden questions the
// gate refused 0 of 8 in-domain unanswerable ones, and the run proved no threshold fixed it, since
// the highest threshold refusing no real question caught none of them. A calibrated 0..1 relevance
// score does have a fixed meaning, so one comparison is the whole rule and the escape hatch is not
// merely unnecessary but wrong: its purpose was to rescue questions a weak embedding misjudged,
// and a cross-encoder that has read the question has not misjudged it.
@Component
@RequiredArgsConstructor
public class ConfidenceGate {

    private final ChatProperties chatProperties;

    // Refuse when there is nothing to answer from at all, and otherwise on whichever confidence
    // signal retrieval produced. Either threshold set to 0 disables its own rule, leaving the
    // empty-result case — which is not a question of confidence and is never disabled.
    public boolean shouldRefuse(RetrievalResult retrieval) {
        if (retrieval.chunks().isEmpty()) {
            return true;
        }
        if (retrieval.topRerankScore().isPresent()) {
            double floor = relevanceThreshold();
            return floor > 0 && retrieval.topRerankScore().getAsDouble() < floor;
        }

        // No rerank: the Phase 5.3 rule, unchanged. It still runs whenever the stage is off, and
        // whenever the stage is on but failed open — a rerank outage must not also take the gate
        // out, or an outage would start answering questions the corpus cannot answer.
        double threshold = threshold();
        if (threshold <= 0) {
            return false;
        }
        boolean weakSemantic = retrieval.topVectorSimilarity().isPresent()
                && retrieval.topVectorSimilarity().getAsDouble() < threshold;
        boolean noLexical = retrieval.lexicalHitCount() == 0;
        return weakSemantic && noLexical;
    }

    // The configured floors. Exposed so a report can state the thresholds its refusal counts were
    // produced under instead of restating constants that config may have moved — the harness this
    // replaced hardcoded 0.30 against a config that had said 0.25 for weeks.
    public double threshold() {
        return chatProperties.minSimilarity();
    }

    public double relevanceThreshold() {
        return chatProperties.minRelevance();
    }
}
