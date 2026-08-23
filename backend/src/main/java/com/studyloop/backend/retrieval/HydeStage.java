package com.studyloop.backend.retrieval;

import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.VectorSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

// Phase 18.2 — a second retrieval pass for the questions the first one answered badly, as a step
// the pipeline can be run with and without.
//
// **Conditional, and the condition is the design.** Unconditional HyDE puts a chat call and an
// embedding call in front of every question, which on a streaming endpoint is a second or more of
// time-to-first-token spent on the three questions in four that did not need it. So the stage reads
// the first pass's own top cosine — a number already computed, before anything has been reranked or
// billed — and only fires when it is weak, or when the question is too short to have retrieved on
// much. On the golden set that is about one question in four.
//
// The trigger is the *cosine* rather than the cross-encoder's relevance, which is the better
// signal, for a reason worth stating: reading the relevance would mean reranking the first pass to
// decide whether to run the second, then reranking again over the combined pool — two paid calls
// and two round trips to avoid one. Fusing both passes and reranking once at the end costs exactly
// what the pipeline cost before, plus the expansion.
//
// **What the second pass produces is more lists, not a replacement ranking.** The hypothetical
// answer searches the dense half; the rewrites search the two lexical halves; and all of it is
// fused with what the first pass found, so HyDE can only add candidates. If the invented passage is
// nonsense, the fused order barely moves and the cross-encoder drops it. That property is why this
// is safe to fail open — see the gate note below for the one place it is not.
@Component
@RequiredArgsConstructor
public class HydeStage {

    private static final Logger log = LoggerFactory.getLogger(HydeStage.class);

    // The same depth as every other list, for the reason VisualStage gives.
    private static final int CANDIDATES = 20;

    private final RetrievalProperties properties;
    private final QueryExpander expander;
    private final EmbeddingClient embeddingClient;
    private final ChunkSearchRepository searchRepository;

    // What a second pass produced: extra rankings to fuse, the best *question*-to-chunk similarity
    // among the chunks it found, and the intent the model read out of the question.
    //
    // `gateSimilarity` is the field this record exists for. It is measured against the original
    // question's vector, never the hypothetical's, so it stays on the scale the confidence gate was
    // calibrated on — while still being allowed to rise, because a chunk HyDE found that is
    // genuinely close to the question *is* a stronger answer than the first pass had. What is
    // excluded is the free lift a pseudo-document-to-document comparison would have given every
    // question equally.
    public record Result(List<List<ChunkHit>> rankings, OptionalDouble gateSimilarity,
                         QueryIntent intent, boolean triggered) {

        static Result notRun() {
            return new Result(List.of(), OptionalDouble.empty(), null, false);
        }
    }

    public boolean enabled() {
        return properties.stages().hyde() && expander.isConfigured() && embeddingClient.isConfigured();
    }

    // Whether this question earned a second pass. Public so the eval harness can report the trigger
    // rate as a measured number rather than restating the threshold and hoping.
    public boolean triggers(String query, OptionalDouble topSimilarity) {
        if (!enabled()) {
            return false;
        }
        RetrievalProperties.Hyde settings = properties.hyde();
        // No semantic signal at all — no embedding provider, or nothing in the corpus came back.
        // Rewriting cannot help a dense search that did not run, but the rewrites still reach the
        // lexical halves, which is exactly the case a vocabulary mismatch produces.
        if (topSimilarity.isEmpty()) {
            return true;
        }
        if (topSimilarity.getAsDouble() < settings.triggerSimilarity()) {
            return true;
        }
        // A short question retrieved on very little even if what it found scored well. "Treaps?" is
        // one term, and one term is one chance at the vocabulary the book happens to use.
        return QueryTerms.of(query, settings.shortQueryTerms() + 1).size() <= settings.shortQueryTerms();
    }

    // The second pass. Never throws: every failure — an unreachable provider, unparseable JSON, an
    // embedding call that 429s — comes back as `notRun()`, and the caller fuses the first pass
    // alone, which is the pipeline Phase 17 shipped.
    public Result apply(UUID courseId, UUID actorId, String query, float[] queryVector,
                        OptionalDouble topSimilarity) {
        if (!triggers(query, topSimilarity)) {
            return Result.notRun();
        }
        QueryExpansion expansion = expander.expand(query);
        if (expansion.isEmpty()) {
            return Result.notRun();
        }

        List<List<ChunkHit>> rankings = new ArrayList<>();
        OptionalDouble gateSimilarity = OptionalDouble.empty();

        if (expansion.hasHypothetical() && queryVector != null) {
            try {
                float[] hypothetical = embeddingClient.embedPseudoDocument(expansion.hypothetical());
                List<ChunkHit> hits = searchRepository.vectorSearch(courseId, actorId,
                        VectorSupport.toLiteral(hypothetical), VectorSupport.toLiteral(queryVector),
                        CANDIDATES);
                if (!hits.isEmpty()) {
                    rankings.add(hits);
                    gateSimilarity = bestGateSimilarity(hits);
                }
            } catch (RuntimeException e) {
                // The rewrites below are still worth running: they cost no provider call, and the
                // call that produced them has already been paid for.
                log.warn("Could not embed the hypothetical answer: {}", e.getMessage());
            }
        }

        // Each rewrite is one more lexical ranking. Not one more dense ranking: a reworded question
        // is a different bag of words, which is what a term index responds to, and roughly the same
        // point in embedding space, which is why paying to embed it would buy a near-duplicate of a
        // list the first pass already has.
        for (String rewrite : expansion.rewrites()) {
            // The same form the first pass used (19.2). A rewrite searched with different
            // semantics from the question would make the two lists incomparable, and RRF is
            // fusing them.
            List<ChunkHit> lexical = searchRepository.fullTextSearch(
                    courseId, actorId, rewrite, CANDIDATES, properties.stages().lexicalOr());
            if (!lexical.isEmpty()) {
                rankings.add(lexical);
            }
            if (properties.stages().trigram()) {
                List<String> terms = QueryTerms.of(rewrite, properties.trigram().maxTerms());
                if (!terms.isEmpty()) {
                    List<ChunkHit> fuzzy =
                            searchRepository.trigramSearch(courseId, actorId, terms, CANDIDATES);
                    if (!fuzzy.isEmpty()) {
                        rankings.add(fuzzy);
                    }
                }
            }
        }
        return new Result(List.copyOf(rankings), gateSimilarity, expansion.intent(), true);
    }

    // The strongest question-to-chunk similarity among the chunks the hypothetical found. A max
    // rather than the head, because this list is *ordered* by the hypothetical: its first row is
    // the best match for the invented passage, which is not necessarily the row closest to what the
    // student actually typed.
    private static OptionalDouble bestGateSimilarity(List<ChunkHit> hits) {
        return hits.stream()
                .map(ChunkHit::cosineSimilarity)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max();
    }
}
