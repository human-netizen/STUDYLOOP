package com.studyloop.backend.retrieval;

import com.studyloop.backend.retrieval.eval.EvalCorpus;
import com.studyloop.backend.retrieval.eval.EvalCorpusConfig;
import com.studyloop.backend.retrieval.eval.FixtureDocument;
import com.studyloop.backend.retrieval.eval.GoldenSet;
import com.studyloop.backend.retrieval.eval.GoldenSet.GoldenQuestion;
import com.studyloop.backend.retrieval.eval.PageGrading;
import com.studyloop.backend.retrieval.eval.PageSpan;
import com.studyloop.backend.retrieval.eval.RetrievalMetrics;
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
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 18.4, the half of it that needs no provider: what the two *lexical* retrievers each find,
// with the question spelled correctly and with one letter pair transposed.
//
// **It exists because the full A/B and this measurement are blocked on different things.** The
// fused pipeline needs a query embedding and a cross-encoder, so measuring it needs a Cohere key
// with quota. The claim 18.1 actually rests on needs neither: both lexical retrievers run entirely
// inside Postgres against chunks that are already in the table, so the question "does a typo empty
// the lexeme list, and does the trigram list still find the page" can be answered exactly,
// deterministically, and for free — on the same fourteen chapters and graded against the same
// golden pages as everything else in this directory.
//
// What it cannot say is what the *fused* numbers do, and that is the point of keeping it separate
// rather than folding it into the report. A sparse-only recall is not comparable with Recall@6 from
// the golden harness — no dense list, no RRF, no reranking — and printing it in the same shape
// would invite exactly that comparison. It answers one question: which of these two term
// retrievers finds the page, under which spelling.
//
// It lives in `retrieval` rather than in `retrieval.eval` because it calls the two SQL queries
// directly, and they are package-private on purpose: everything else reaches them through
// RetrievalService, which is the seam that adds the embedding call this test is avoiding.
//
// A real database and the seeded fixture corpus, so it stays behind a system property:
//     ./mvnw -Deval.lexical=true -Dtest=LexicalTypoEvalTest test
@SpringBootTest(properties = "studyloop.summary.auto-generate=false")
@Import(EvalCorpusConfig.class)
@EnabledIfSystemProperty(named = "eval.lexical", matches = "true")
class LexicalTypoEvalTest {

    // The same six the golden harness reports at, so the two are at least measuring the same depth.
    private static final int K = 6;

    @Autowired
    private EvalCorpus evalCorpus;

    @Autowired
    private ChunkSearchRepository searchRepository;

    @Test
    void measuresWhatATypoCostsEachLexicalRetriever() {
        EvalCorpus.Seeded corpus = evalCorpus.ensureSeeded();
        GoldenSet clean = GoldenSet.load();
        GoldenSet misspelled = clean.withTypos();

        Result lexemeClean = run(corpus, clean, Retriever.LEXEME);
        Result lexemeTypo = run(corpus, misspelled, Retriever.LEXEME);
        Result trigramClean = run(corpus, clean, Retriever.TRIGRAM);
        Result trigramTypo = run(corpus, misspelled, Retriever.TRIGRAM);

        String rendered = render(corpus, lexemeClean, lexemeTypo, trigramClean, trigramTypo);
        System.out.println(rendered);
        System.out.println("report written to " + save(rendered).toAbsolutePath() + "\n");

        assertThat(corpus.chunks())
                .as("the fixture corpus did not ingest — every number above would be zero")
                .isGreaterThan(0);
        // The failure this stage repairs, asserted rather than described. One transposed letter
        // pair makes `plainto_tsquery` an AND over a lexeme no chunk carries, so the sparse half
        // does not weaken — it returns nothing, for every question in the set.
        assertThat(lexemeTypo.questionsWithNoHits())
                .as("a typo is expected to empty the lexeme list for every question")
                .isEqualTo(lexemeTypo.questions());
        // **And the finding this run turned up, which is larger than the one it was written for.**
        // The lexeme list is mostly empty on *correctly spelled* questions too. `plainto_tsquery`
        // ANDs every content word, so "What running time does a LinearHashTable guarantee for add,
        // remove and find?" becomes `run & time & linearhasht & guarante & add & remov & find` and
        // matches only a chunk containing all seven. On this corpus that is 10 questions in 56.
        //
        // Asserted as a relationship rather than as the number, because the number is a property of
        // this corpus and the relationship is a property of the two query forms: one ANDs and
        // returns nothing unless every word is present, the other ORs and ranks by how many matched.
        // If a later phase changes the lexeme query, this is the assertion that should change with
        // it — see BUGS.md.
        assertThat(lexemeClean.questionsWithNoHits())
                .as("plainto_tsquery ANDs the question's words, so most questions match nothing")
                .isGreaterThan(lexemeClean.questions() / 2);
        // The repair: the trigram list survives the same perturbation, and outranks the lexeme list
        // even before anything is misspelled.
        assertThat(trigramTypo.questionsWithNoHits()).isZero();
        assertThat(trigramClean.recall()).isGreaterThan(lexemeClean.recall());
        assertThat(trigramTypo.recall())
                .as("the trigram list should lose little to a single transposition")
                .isGreaterThan(trigramClean.recall() * 0.8);
    }

    private enum Retriever { LEXEME, TRIGRAM }

    private record Result(String label, int questions, int questionsWithNoHits,
                          double recall, double mrr) { }

    private Result run(EvalCorpus.Seeded corpus, GoldenSet golden, Retriever retriever) {
        List<Double> recalls = new ArrayList<>();
        List<Double> reciprocals = new ArrayList<>();
        int empty = 0;
        for (GoldenQuestion question : golden.answerable()) {
            List<ChunkHit> hits = switch (retriever) {
                case LEXEME -> searchRepository.fullTextSearch(
                        corpus.courseId(), corpus.actorId(), question.question(), K);
                case TRIGRAM -> {
                    List<String> terms = QueryTerms.of(question.question(), 8);
                    yield terms.isEmpty() ? List.of() : searchRepository.trigramSearch(
                            corpus.courseId(), corpus.actorId(), terms, K);
                }
            };
            if (hits.isEmpty()) {
                empty++;
            }
            List<Double> gains = PageGrading.gradeSpans(spansOf(hits), question.expected(),
                    golden.pageTolerance());
            recalls.add(RetrievalMetrics.recallAt(gains, question.totalRelevant()));
            reciprocals.add(RetrievalMetrics.reciprocalRank(gains));
        }
        return new Result(retriever.name().toLowerCase(Locale.ROOT), recalls.size(), empty,
                mean(recalls), mean(reciprocals));
    }

    // The same mapping the golden harness uses: a chunk from a document this corpus did not seed, or
    // one with no page, becomes null and is graded 0 rather than dropped — it still took a slot.
    private static List<PageSpan> spansOf(List<ChunkHit> hits) {
        List<PageSpan> spans = new ArrayList<>(hits.size());
        for (ChunkHit hit : hits) {
            FixtureDocument document = FixtureDocument.byFileName(hit.filename());
            spans.add(document == null || hit.pageNumber() == null
                    ? null
                    : PageSpan.of(document, hit.pageNumber(), hit.pageEnd()));
        }
        return spans;
    }

    private static String render(EvalCorpus.Seeded corpus, Result... results) {
        StringBuilder out = new StringBuilder("\n===== lexical retrievers under a typo (18.4) =====\n");
        out.append("corpus        %d documents / %d chunks%n".formatted(corpus.documents(), corpus.chunks()));
        out.append("questions     %d answerable%n".formatted(results[0].questions()));
        out.append("k             %d   (one retriever alone: no dense list, no fusion, no rerank)%n"
                .formatted(K));
        out.append("typo          one transposed letter pair in the longest word of each question%n%n"
                .formatted());
        out.append(String.format(Locale.ROOT, "%-12s %-12s %8s %8s %10s%n",
                "retriever", "spelling", "recall", "MRR", "no hits"));
        String[] spelling = {"correct", "misspelled", "correct", "misspelled"};
        for (int i = 0; i < results.length; i++) {
            Result result = results[i];
            out.append(String.format(Locale.ROOT, "%-12s %-12s %8.3f %8.3f %6d/%-3d%n",
                    result.label(), spelling[i], result.recall(), result.mrr(),
                    result.questionsWithNoHits(), result.questions()));
        }
        return out.toString();
    }

    // Beside the golden harness's reports rather than only on the console, for the reason that
    // one gives: a number that exists solely in a scrolled-past terminal is one somebody will end up
    // remembering rather than reading.
    private static Path save(String rendered) {
        Path file = Path.of("target", "eval").resolve("lexical-%s.txt".formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, rendered);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the lexical eval report", e);
        }
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
