package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.chat.ConfidenceGate;
import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.retrieval.eval.GoldenSet.GoldenQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 11.1 — the retrieval baseline every later phase is measured against.
//
// Seeds a course from the fourteen committed fixture PDFs, runs all sixty golden questions through
// the real hybrid retrieval pipeline, and reports Recall@6, MRR and nDCG@6 for the answerable ones
// plus the confidence gate's refusal rate on the deliberately unanswerable ones.
//
// It calls RetrievalService and ConfidenceGate rather than the chat endpoint. Everything the report
// measures is decided before the model is reached — which chunks came back, in what order, and
// whether the gate let the turn through — so putting sixty Command R calls in front of those
// numbers would add cost, latency and the model's own variance to a measurement of retrieval.
//
// Real Cohere and a real database, so it stays behind a system property and out of CI:
//     ./mvnw -Deval.golden=true -Dtest=RetrievalEvalTest test
// Add -Deval.reset=true to rebuild the corpus from scratch. That is the right thing to do when the
// ingestion pipeline itself changed — new chunker, new embedding model, new extraction — because
// the chunks already in the database were produced by the old one, and a comparison against them
// would credit the phase with the pipeline's change.
@SpringBootTest(properties = {
        // Fourteen summary calls that nothing here reads. Ingestion generates them by default, and
        // the eval's corpus is not a course anyone browses.
        "studyloop.summary.auto-generate=false",
        // application-test.yml blanks this so no ordinary test can reach a paid reranker by
        // accident. This is the one test whose entire job is to measure the real one, so it hands
        // the key back — inlined properties outrank the profile's. Still blank when the environment
        // has no key, which leaves the stage off and is reported as such rather than guessed at.
        "studyloop.retrieval.rerank.api-key=${COHERE_API_KEY:}"
})
@Import(EvalCorpusConfig.class)
@EnabledIfSystemProperty(named = "eval.golden", matches = "true")
class RetrievalEvalTest {

    // Matches ChatService.RETRIEVAL_K: the report has to describe the prompt chat actually builds,
    // not a more generous top-k that would make retrieval look better than the answer it feeds.
    private static final int K = 6;

    // Not a quality target. Nothing in this phase claims a number is good enough — the point of the
    // run is to establish what the numbers *are*. This exists so the test fails loudly when the
    // harness is measuring nothing rather than measuring something bad: an empty corpus or a
    // half-ingested one produces a plausible-looking report full of zeroes, and a test that prints
    // it without complaint would be a spreadsheet, not a test.
    //
    // There is deliberately no floor on refusal rate. The first run of this harness scored 0/8, and
    // that is a true reading of the gate rather than a broken instrument — see the separability
    // section of the report. Asserting on it would make every run red until Phase 12.2 lands, and a
    // test that is expected to be red is a test nobody reads.
    private static final double RECALL_FLOOR = 0.40;

    // Rerank calls a minute this run is allowed to make. Cohere's trial key permits ten, and this
    // harness asks sixty questions as fast as retrieval can answer them — so unpaced, forty of them
    // come back 429, the stage fails open exactly as designed, and the report describes the fused
    // pipeline for two-thirds of its rows while its header says rerank=ON. Pacing it is the
    // harness's problem, not the client's: no student generates this traffic, and a retry loop
    // inside the request path would spend a real user's latency budget on the eval's convenience.
    //
    // -Deval.rerank-rpm=0 removes the pacing, which is what a production key wants.
    //
    // Eight rather than the ten the key nominally allows. At 9.6 calls a minute one question in
    // sixty still came back 429, so the provider's window is a sliding one and pacing to the
    // nominal rate lands on its edge. The margin costs ninety seconds on a run that already takes
    // seven minutes, against a failed run that has to be repeated in full.
    private static final int TRIAL_KEY_RERANK_RPM = 8;
    private static final long PACE_NANOS = paceNanos();

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private ConfidenceGate confidenceGate;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Autowired
    private EvalCorpus evalCorpus;

    @Test
    void measuresRetrievalAgainstTheGoldenSet() {
        if (Boolean.getBoolean("eval.reset")) {
            evalCorpus.reset();
        }
        EvalCorpus.Seeded corpus = evalCorpus.ensureSeeded();
        GoldenSet golden = GoldenSet.load();

        // A document the golden set never accounted for changes what retrieval is choosing between,
        // which silently changes every number below. Cheap to check, impossible to spot afterwards.
        assertThat(evalCorpus.strayDocuments(corpus.courseId()))
                .as("the eval course holds documents that are not fixtures — run with -Deval.reset=true")
                .isEmpty();

        EvalReport report = new EvalReport(K, golden.pageTolerance(), confidenceGate.threshold(),
                confidenceGate.relevanceThreshold(), retrievalProperties.stages().describe(), corpus);

        boolean paced = retrievalProperties.stages().rerank() && PACE_NANOS > 0;
        long nextSlot = System.nanoTime();
        for (GoldenQuestion question : golden.questions()) {
            if (paced) {
                nextSlot = awaitSlot(nextSlot);
            }
            RetrievalResult result =
                    retrievalService.search(corpus.actorId(), corpus.courseId(), question.question(), K);
            report.add(score(question, result, golden.pageTolerance()));
        }

        String rendered = report.render();
        System.out.println(rendered);
        Path saved = save(rendered);
        System.out.println("report written to " + saved.toAbsolutePath() + "\n");

        assertThat(corpus.chunks())
                .as("the fixture corpus did not ingest — every metric above would be zero")
                .isGreaterThan(0);
        assertThat(report.size())
                .as("not every golden question was scored")
                .isEqualTo(golden.questions().size());
        assertThat(report.answerableWithoutSemanticSignal())
                .as("questions that ran without a vector search — this report describes full-text "
                        + "retrieval, not the hybrid pipeline it claims to")
                .isZero();
        if (retrievalProperties.stages().rerank()) {
            // The sibling of the check above, and it exists because the first run of this stage
            // produced exactly the report it is meant to stop: a header saying rerank=ON over
            // averages two-thirds of which came from the fused order, because a trial key's rate
            // limit turned the fail-open path into the normal path. Failing open is right in
            // production and wrong in a measurement.
            assertThat(report.rerankFallbacks())
                    .as("the rerank stage is on but %d questions fell back to the fused order — "
                            + "this report is a blend of two pipelines. Usually the provider's rate "
                            + "limit: pace the run with -Deval.rerank-rpm=<calls per minute>",
                            report.rerankFallbacks())
                    .isZero();
        }
        // Phase 14.3. The corpus is built at ingest, so this flag and the chunks in the database
        // can disagree, and every number in the report above would be a description of the wrong
        // pipeline under a header naming the right one. The check is categorical rather than a
        // coverage floor: a partly generated corpus is possible but a provider failure fails the
        // ingest, while forgetting -Deval.reset=true after flipping the flag is the mistake that
        // actually happens, and it lands squarely on one side or the other.
        if (retrievalProperties.stages().syntheticQueries()) {
            assertThat(corpus.synthetic())
                    .as("synthetic queries are on but no chunk in the corpus carries a generated "
                            + "block — this corpus was ingested without them. Rerun with "
                            + "-Deval.reset=true")
                    .isGreaterThan(0);
        } else {
            assertThat(corpus.synthetic())
                    .as("%d chunks carry a synthetic-query block but this run claims the pipeline "
                            + "without them — rerun with -Deval.reset=true", corpus.synthetic())
                    .isZero();
        }
        assertThat(report.recall())
                .as("Recall@%d collapsed; the harness is not measuring retrieval (see the report above)", K)
                .isGreaterThanOrEqualTo(RECALL_FLOOR);
    }

    private EvalReport.Row score(GoldenQuestion question, RetrievalResult result, int tolerance) {
        List<PageSpan> retrieved = spansOf(result);
        Double topSimilarity = result.topVectorSimilarity().isPresent()
                ? result.topVectorSimilarity().getAsDouble()
                : null;
        Double topRelevance = result.topRerankScore().isPresent()
                ? result.topRerankScore().getAsDouble()
                : null;
        boolean refused = confidenceGate.shouldRefuse(result);

        // An unanswerable question has no correct page, so ranking it is meaningless — the metrics
        // throw rather than return 0.0, and the only thing it is scored on is whether the gate
        // declined it. Recording zeroes here instead would drag the averages down and make the
        // refusal set read as a retrieval regression.
        if (!question.answerable()) {
            return new EvalReport.Row(question, retrieved, 0.0, 0.0, 0.0,
                    topSimilarity, topRelevance, result.lexicalHitCount(), refused);
        }

        List<Double> gains = PageGrading.gradeSpans(retrieved, question.expected(), tolerance);
        return new EvalReport.Row(question, retrieved,
                RetrievalMetrics.recallAt(gains, question.totalRelevant()),
                RetrievalMetrics.reciprocalRank(gains),
                RetrievalMetrics.ndcgAt(gains, question.totalRelevant()),
                topSimilarity, topRelevance, result.lexicalHitCount(), refused);
    }

    // Waits until this question's slot in the rate limit comes round, and returns the next one.
    // Retrieval time counts toward the gap rather than being added to it, so pacing a run costs
    // what the limit costs and nothing more.
    private static long awaitSlot(long slot) {
        long wait = slot - System.nanoTime();
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("eval run interrupted while pacing", e);
            }
        }
        return System.nanoTime() + PACE_NANOS;
    }

    private static long paceNanos() {
        int rpm = Integer.getInteger("eval.rerank-rpm", TRIAL_KEY_RERANK_RPM);
        if (rpm <= 0) {
            return 0;
        }
        return (60_000L / rpm) * 1_000_000L;
    }

    // Retrieval order, as places in the corpus. A chunk with no page, or from a document this
    // corpus did not seed, becomes null: PageGrading scores it 0 rather than dropping it, because
    // it still consumed one of the six slots a relevant chunk could have had.
    private static List<PageSpan> spansOf(RetrievalResult result) {
        List<PageSpan> spans = new ArrayList<>(result.chunks().size());
        for (RetrievedChunk chunk : result.chunks()) {
            FixtureDocument document = FixtureDocument.byFileName(chunk.filename());
            spans.add(document == null || chunk.pageNumber() == null
                    ? null
                    : PageSpan.of(document, chunk.pageNumber(), chunk.pageEnd()));
        }
        return spans;
    }

    // Kept alongside the build output rather than printed only to the console: Phase 12 onward each
    // compare against "the 11.1 baseline", and a baseline that exists solely in a scrolled-past
    // terminal is a number somebody will end up remembering rather than reading.
    private static Path save(String rendered) {
        Path directory = Path.of("target", "eval");
        Path file = directory.resolve("retrieval-%s.txt".formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
        try {
            Files.createDirectories(directory);
            Files.writeString(file, rendered);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the eval report", e);
        }
    }
}
