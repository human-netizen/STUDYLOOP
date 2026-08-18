package com.studyloop.backend.chat;

import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.document.DocumentSource;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The refusal policy on its own — no Spring, no database, no provider.
//
// It was a private method inside ChatService until Phase 11.1, which meant the only way to reach it
// was through a full RAG turn. Every one of the cases below was therefore untested, including the
// lexical escape hatch, which is the half that decides whether an exact-keyword question survives a
// weak embedding. The eval harness reads the same object, so the refusal rate it reports is this
// policy's and not a restatement of it.
//
// Two rules since Phase 12.2, so the cases come in two blocks: what the gate does with a calibrated
// rerank relevance, and what it still does without one.
class ConfidenceGateTest {

    private static final double THRESHOLD = 0.25;
    private static final double RELEVANCE = 0.30;

    private final ConfidenceGate gate = gateWithThreshold(THRESHOLD);

    @Test
    void refusesWhenNothingWasRetrieved() {
        assertThat(gate.shouldRefuse(result(List.of(), null, 0))).isTrue();
    }

    // ── the rerank rule (Phase 12.2) ────────────────────────────────────────────────────────

    @Test
    void refusesWhenTheRerankerFoundNothingRelevant() {
        assertThat(gate.shouldRefuse(reranked(oneChunk(), 0.02, 0))).isTrue();
    }

    @Test
    void answersWhenTheRerankerFoundSomethingRelevant() {
        assertThat(gate.shouldRefuse(reranked(oneChunk(), 0.88, 0))).isFalse();
    }

    // The escape hatch exists to rescue a question a weak *embedding* misjudged. A cross-encoder
    // that read the question and the passage together has not misjudged it, so keeping the hatch
    // would reinstate exactly the failure 12.2 was built to fix: an in-domain question the corpus
    // cannot answer shares vocabulary with the corpus by definition, and would be answered anyway.
    @Test
    void aRelevanceRefusalIsNotRescuedByMatchingWords() {
        assertThat(gate.shouldRefuse(reranked(oneChunk(), 0.02, 7))).isTrue();
    }

    // The cosine is still carried on the result and is still strong here. It must not get a vote:
    // the two scales disagree by construction, and a gate that refused only when both agreed would
    // be the old gate with extra steps.
    @Test
    void aStrongCosineDoesNotOverrideAWeakRelevance() {
        assertThat(gate.shouldRefuse(reranked(oneChunk(), 0.02, 0, 0.71))).isTrue();
    }

    @Test
    void aRelevanceThresholdOfZeroDisablesTheRerankRule() {
        ConfidenceGate disabled = new ConfidenceGate(
                new ChatProperties("cohere", new ChatProperties.Cohere("key", "command-r"), THRESHOLD, 0));

        assertThat(disabled.shouldRefuse(reranked(oneChunk(), 0.001, 0))).isFalse();
        assertThat(disabled.shouldRefuse(result(List.of(), null, 0))).isTrue();
    }

    // ── the cosine rule, for when nothing reranked ──────────────────────────────────────────

    @Test
    void refusesWhenTheSemanticMatchIsWeakAndNothingMatchedLexically() {
        assertThat(gate.shouldRefuse(result(oneChunk(), 0.11, 0))).isTrue();
    }

    @Test
    void answersWhenTheSemanticMatchIsStrong() {
        assertThat(gate.shouldRefuse(result(oneChunk(), 0.62, 0))).isFalse();
    }

    @Test
    void answersOnAWeakEmbeddingIfTheWordsMatched() {
        // The escape hatch. "What is a treap?" is three words long and may embed weakly against a
        // chapter that spells it out; refusing that while the term is sitting in the full-text
        // index would be the gate failing at the one job it has.
        assertThat(gate.shouldRefuse(result(oneChunk(), 0.11, 4))).isFalse();
    }

    @Test
    void answersOnTheBoundary() {
        // Exactly at the threshold is not below it. Stated as a test because the whole policy is
        // one comparison, and which way it leans at the boundary is a decision, not an accident.
        assertThat(gate.shouldRefuse(result(oneChunk(), THRESHOLD, 0))).isFalse();
    }

    @Test
    void answersFromLexicalHitsAloneWhenNoVectorSearchRan() {
        // No embedding provider configured: retrieval degrades to full-text, and there is no
        // similarity to compare. Refusing here would take chat down with the embedding provider.
        assertThat(gate.shouldRefuse(result(oneChunk(), null, 3))).isFalse();
    }

    @Test
    void aThresholdOfZeroDisablesTheSemanticHalf() {
        ConfidenceGate disabled = gateWithThreshold(0);
        assertThat(disabled.shouldRefuse(result(oneChunk(), 0.01, 0))).isFalse();
        // The empty case still refuses: there is nothing to ground an answer on at all, which is
        // not a question of confidence.
        assertThat(disabled.shouldRefuse(result(List.of(), null, 0))).isTrue();
    }

    @Test
    void reportsTheConfiguredThresholds() {
        // The eval report prints these rather than constants of its own. The harness this replaced
        // hardcoded 0.30 against a config that had said 0.25 for weeks.
        assertThat(gate.threshold()).isEqualTo(THRESHOLD);
        assertThat(gate.relevanceThreshold()).isEqualTo(RELEVANCE);
    }

    private static ConfidenceGate gateWithThreshold(double threshold) {
        return new ConfidenceGate(new ChatProperties(
                "cohere", new ChatProperties.Cohere("key", "command-r"), threshold, RELEVANCE));
    }

    private static RetrievalResult result(List<RetrievedChunk> chunks, Double topSimilarity, int lexicalHits) {
        return new RetrievalResult(chunks,
                topSimilarity == null ? OptionalDouble.empty() : OptionalDouble.of(topSimilarity),
                lexicalHits, OptionalDouble.empty(), null);
    }

    // A retrieval the rerank stage ran on. The cosine defaults to a value well above the old
    // threshold so that a case which expects a refusal can only be getting it from the relevance.
    private static RetrievalResult reranked(List<RetrievedChunk> chunks, double relevance, int lexicalHits) {
        return reranked(chunks, relevance, lexicalHits, 0.55);
    }

    private static RetrievalResult reranked(List<RetrievedChunk> chunks, double relevance,
                                            int lexicalHits, double topSimilarity) {
        return new RetrievalResult(chunks, OptionalDouble.of(topSimilarity), lexicalHits,
                OptionalDouble.of(relevance), null);
    }

    private static List<RetrievedChunk> oneChunk() {
        return List.of(new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), "lecture.pdf",
                DocumentSource.UPLOAD, 4, 4, "Chapter 1 > 1.2 Recurrences", "some course material",
                12, 0.016, 0.42, null));
    }
}
