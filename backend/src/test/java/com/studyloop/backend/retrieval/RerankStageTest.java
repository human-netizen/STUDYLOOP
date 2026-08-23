package com.studyloop.backend.retrieval;

import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.config.RetrievalProperties.Rerank;
import com.studyloop.backend.config.RetrievalProperties.Stages;
import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.DocumentSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The rerank stage against a fake cross-encoder — no Spring, no network, no key.
//
// A stub client is the right instrument here because none of what this class decides is about the
// provider's judgement. It is about what happens around the call: how deep to fuse before making
// it, whether the returned order is applied faithfully, and what the pipeline does when the call
// fails. The last one is the only path in Phase 12 that a user is likely to meet, and it is
// unreachable through the real client without taking Cohere offline.
class RerankStageTest {

    private static final int TOP_N = 3;

    @Test
    void aStageThatIsSwitchedOffChangesNothingButTheLength() {
        RerankStage stage = stage(false, alwaysReverses());

        List<RetrievedChunk> result = stage.apply("query", chunks(5), TOP_N);

        assertThat(stage.enabled()).isFalse();
        // The baseline pipeline, byte for byte: fused order, truncated, and no relevance scores to
        // make the confidence gate think a cross-encoder had an opinion.
        assertThat(contentsOf(result)).containsExactly("c0", "c1", "c2");
        assertThat(result).allSatisfy(chunk -> assertThat(chunk.rerankScore()).isNull());
    }

    @Test
    void aStageThatIsSwitchedOffAsksForNoExtraCandidates() {
        assertThat(stage(false, alwaysReverses()).candidatePool(TOP_N)).isEqualTo(TOP_N);
    }

    // The whole point of the stage. Reranking the six chunks that were going to be used anyway can
    // only reorder six chunks; the passage RRF ranked eleventh is the one it exists to promote.
    @Test
    void aStageThatIsOnFusesDeepEnoughForThereToBeSomethingToPromote() {
        RerankStage stage = stage(true, alwaysReverses());

        assertThat(stage.candidatePool(TOP_N)).isEqualTo(30);
        // Never below what the caller asked for, whatever the pool is configured to.
        assertThat(stage.candidatePool(64)).isEqualTo(64);
    }

    @Test
    void theCrossEncodersOrderReplacesTheFusedOneAndItsScoresComeBackWithIt() {
        RerankStage stage = stage(true, alwaysReverses());

        List<RetrievedChunk> result = stage.apply("query", chunks(5), TOP_N);

        assertThat(contentsOf(result)).containsExactly("c4", "c3", "c2");
        assertThat(result.get(0).rerankScore()).isEqualTo(1.0);
        // The fused RRF score survives untouched beside it. They are different scales measuring
        // different things, and a report that could not tell them apart could not say which
        // pipeline produced its numbers.
        assertThat(result.get(0).score()).isEqualTo(scoreOf(4));
    }

    // Fail open. A rerank outage falls back to the fused order rather than failing the request: a
    // slightly worse answer beats a 502 on a question the corpus could have answered.
    @Test
    void aProviderOutageFallsBackToTheFusedOrder() {
        RerankStage stage = stage(true, (query, documents, topN) -> {
            throw new RerankException("Cohere rerank request failed: 503");
        });

        List<RetrievedChunk> result = stage.apply("query", chunks(5), TOP_N);

        assertThat(contentsOf(result)).containsExactly("c0", "c1", "c2");
        // No scores, which is what tells the confidence gate to fall back to its cosine rule. A
        // fabricated relevance here would leave the gate comparing against a threshold on a scale
        // nothing produced — and it would do so precisely when the pipeline is already degraded.
        assertThat(result).allSatisfy(chunk -> assertThat(chunk.rerankScore()).isNull());
    }

    @Test
    void aProviderThatRanksNothingIsTreatedAsAnOutage() {
        RerankStage stage = stage(true, (query, documents, topN) -> List.of());

        assertThat(contentsOf(stage.apply("query", chunks(5), TOP_N)))
                .containsExactly("c0", "c1", "c2");
    }

    // The indices are the provider's and are used to index an array. One that points past the end
    // costs its slot and nothing else — the alternative is an IndexOutOfBoundsException reaching a
    // student mid-question because a response shape changed.
    @Test
    void anIndexOutsideTheCandidateSetIsSkippedRatherThanThrown() {
        RerankStage stage = stage(true, (query, documents, topN) -> List.of(
                new RerankClient.Ranked(99, 0.9),
                new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(-1, 0.7),
                new RerankClient.Ranked(3, 0.6)));

        assertThat(contentsOf(stage.apply("query", chunks(5), TOP_N))).containsExactly("c1", "c3");
    }

    @Test
    void neverReturnsMoreThanTheCallerAskedFor() {
        RerankStage stage = stage(true, (query, documents, topN) -> {
            // A provider ignoring top_n. The stage owns the prompt's size, not the provider.
            List<RerankClient.Ranked> ranked = new ArrayList<>();
            for (int index = 0; index < documents.size(); index++) {
                ranked.add(new RerankClient.Ranked(index, 1.0 - index * 0.01));
            }
            return ranked;
        });

        assertThat(stage.apply("query", chunks(10), TOP_N)).hasSize(TOP_N);
    }

    // An enabled stage with no key would otherwise pay for over-retrieval — 30 candidates fused
    // instead of 6 — on every question, in exchange for a call that is never made.
    @Test
    void aStageWithNoApiKeyIsOffHoweverTheSwitchIsSet() {
        RerankStage stage = new RerankStage(
                new RetrievalProperties(new Stages(true, false, false, false, false, false, false),
                        new Rerank(" ", null, 0), null, null),
                new StubRerankClient(false, alwaysReverses()));

        assertThat(stage.enabled()).isFalse();
        assertThat(stage.candidatePool(TOP_N)).isEqualTo(TOP_N);
    }

    @Test
    void aSingleCandidateIsNotWorthACall() {
        boolean[] called = {false};
        RerankStage stage = stage(true, (query, documents, topN) -> {
            called[0] = true;
            return List.of(new RerankClient.Ranked(0, 0.9));
        });

        assertThat(stage.apply("query", chunks(1), TOP_N)).hasSize(1);
        assertThat(called[0]).as("there is nothing to reorder, so nothing to pay for").isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static RerankStage stage(boolean on, RerankFunction rerank) {
        return new RerankStage(
                new RetrievalProperties(new Stages(on, false, false, false, false, false, false),
                        new Rerank("key", null, 0), null, null),
                new StubRerankClient(true, rerank));
    }

    // Ranks the candidates in reverse, best first — a deterministic disagreement with the fused
    // order, so a test can tell "the reranker's order was applied" from "the list came back".
    private static RerankFunction alwaysReverses() {
        return (query, documents, topN) -> {
            List<RerankClient.Ranked> ranked = new ArrayList<>();
            for (int index = documents.size() - 1; index >= 0 && ranked.size() < topN; index--) {
                ranked.add(new RerankClient.Ranked(index, (index + 1.0) / documents.size()));
            }
            return ranked;
        };
    }

    private static List<RetrievedChunk> chunks(int count) {
        List<RetrievedChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            chunks.add(new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), "lecture.pdf",
                    DocumentSource.UPLOAD, index + 1, index + 1, null, "c" + index, 8,
                    ChunkModality.TEXT, scoreOf(index), 0.42, null));
        }
        return chunks;
    }

    // Descending, like a fused list: candidate 0 is the one RRF liked best.
    private static double scoreOf(int index) {
        return 1.0 / (60 + index + 1);
    }

    private static List<String> contentsOf(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::content).toList();
    }

    @FunctionalInterface
    private interface RerankFunction {
        List<RerankClient.Ranked> rerank(String query, List<String> documents, int topN);
    }

    private record StubRerankClient(boolean configured, RerankFunction delegate) implements RerankClient {

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public List<Ranked> rerank(String query, List<String> documents, int topN) {
            return delegate.rerank(query, documents, topN);
        }
    }
}
