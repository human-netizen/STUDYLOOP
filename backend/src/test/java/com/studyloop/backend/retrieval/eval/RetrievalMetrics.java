package com.studyloop.backend.retrieval.eval;

import java.util.ArrayList;
import java.util.List;

// Rank-aware retrieval metrics. Lives in the test tree on purpose: nothing in production reads
// these, and shipping measurement code in the application jar invites someone to call it.
//
// Every method takes the same shape — `gains`, the relevance grade of each retrieved chunk in the
// order retrieval returned it, and `totalRelevant`, how many relevant items existed to be found.
// Turning "chunk on page 24" into a grade is a corpus-specific rule and lives in PageGrading; what
// is here is the arithmetic, which is the same for every corpus and is worth testing on its own.
//
// Why three numbers instead of the boolean hit-rate this replaces: a hit-rate says the right page
// appeared somewhere in the top 6 and nothing else. It cannot distinguish "first result" from
// "sixth result", and it cannot distinguish "found one of the three pages that mattered" from
// "found all three". Both distinctions are exactly what the changes after this are trying to move.
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    // Recall@k — of everything that should have been found, what fraction was?
    //
    // Answers "is the evidence there at all". This is the metric that a reranker cannot improve:
    // reranking reorders the candidates retrieval already returned, so if the right page was never
    // a candidate, no amount of reordering conjures it. When recall is the number that is stuck,
    // the fix is upstream — chunking, extraction, query rewriting — not ranking.
    public static double recallAt(List<Double> gains, int totalRelevant) {
        requireAnswerable(totalRelevant);
        long found = gains.stream().filter(g -> g > 0).count();
        // Clamped because a caller could credit more items than it declared relevant; that is a
        // bug in the grading rule, and silently reporting recall of 1.4 would hide it.
        return Math.min((double) found / totalRelevant, 1.0);
    }

    // Reciprocal rank — 1/(position of the first relevant result), 0 if there is none. Averaged
    // over the question set this is MRR.
    //
    // Answers "how far does the reader have to look". The drop is deliberately steep: 1.0, 0.50,
    // 0.33, 0.25. That matches what the gate and the prompt actually do with the list — the top
    // chunks dominate the assembled context, and the confidence gate reads the best match alone —
    // so a change that moves the right chunk from rank 4 to rank 1 is a real improvement that
    // recall, by construction, reports as zero change.
    public static double reciprocalRank(List<Double> gains) {
        for (int i = 0; i < gains.size(); i++) {
            if (gains.get(i) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    // Normalized discounted cumulative gain — the retrieved ordering's DCG over the DCG of the
    // best ordering that was possible for this question. 1.0 means "nothing could have been
    // ranked better", 0.0 means nothing relevant was retrieved.
    //
    // Answers "how good is the whole ordering", which neither metric above does: recall ignores
    // position entirely, and reciprocal rank ignores everything after the first hit. For a
    // question whose answer is spread over three pages, nDCG is the only one of the three that
    // notices whether the other two pages came back second and third or fifth and sixth.
    //
    // Normalizing matters here specifically because questions in the golden set have different
    // numbers of expected pages. Raw DCG would make a three-page question score higher than a
    // one-page question purely for having more to find, and the average across the set would then
    // be steered by the shape of the question mix rather than by retrieval quality.
    public static double ndcgAt(List<Double> gains, int totalRelevant) {
        requireAnswerable(totalRelevant);
        double idcg = dcg(idealGains(gains.size(), totalRelevant));
        if (idcg == 0.0) {
            return 0.0;
        }
        return dcg(gains) / idcg;
    }

    // Discounted cumulative gain: each hit is worth its grade, divided by log2 of its 1-based
    // position + 1. Rank 1 divides by log2(2) = 1, so the first result is undiscounted.
    private static double dcg(List<Double> gains) {
        double sum = 0.0;
        for (int i = 0; i < gains.size(); i++) {
            sum += gains.get(i) / (Math.log(i + 2) / Math.log(2));
        }
        return sum;
    }

    // The best ordering available: every relevant item first, as many as fit in k, then nothing.
    private static List<Double> idealGains(int k, int totalRelevant) {
        List<Double> ideal = new ArrayList<>(k);
        int hits = Math.min(totalRelevant, k);
        for (int i = 0; i < k; i++) {
            ideal.add(i < hits ? 1.0 : 0.0);
        }
        return ideal;
    }

    // A question with nothing relevant to find is not a badly-answered retrieval question; it is a
    // different question. The golden set includes deliberately unanswerable questions and they are
    // scored on whether chat refused, never on rank. Returning 0.0 for them instead of refusing
    // would drag every average toward zero and make the refusal set look like a retrieval
    // regression — which is the precise failure this harness exists to be able to rule out.
    private static void requireAnswerable(int totalRelevant) {
        if (totalRelevant <= 0) {
            throw new IllegalArgumentException(
                    "No relevant items declared. Unanswerable questions are scored by refusal rate, not by rank.");
        }
    }
}
