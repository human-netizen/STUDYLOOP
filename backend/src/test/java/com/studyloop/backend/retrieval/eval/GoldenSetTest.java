package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.document.PdfTextExtractor;
import com.studyloop.backend.retrieval.eval.GoldenSet.GoldenQuestion;
import com.studyloop.backend.retrieval.eval.GoldenSet.Kind;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Structural validation of the golden set, with no Spring, no database and no provider, so it runs
// in CI. It does not measure retrieval — it checks that the thing retrieval will be measured
// against is internally consistent.
//
// The check that earns its keep is pagesExistInTheDocumentTheyName: expected pages were read out of
// the PDFs by hand, and a transposed digit produces a question that can never be satisfied and a
// permanent, invisible drag on every metric the harness reports.
class GoldenSetTest {

    private static final int MINIMUM_QUESTIONS = 55;

    private final GoldenSet goldenSet = GoldenSet.load();

    @Test
    void isLargeEnoughForAPercentagePointToMeanSomething() {
        // At twenty questions each is worth five points, so noise and a real gain are the same
        // number. The whole reason this set was rebuilt.
        assertThat(goldenSet.questions()).hasSizeGreaterThanOrEqualTo(MINIMUM_QUESTIONS);
    }

    @Test
    void coversEveryKindOfQuestion() {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (GoldenQuestion q : goldenSet.questions()) {
            counts.merge(q.kind(), 1, Integer::sum);
        }
        System.out.println("\ngolden set by kind: " + counts + "\n");

        for (Kind kind : Kind.values()) {
            assertThat(counts.getOrDefault(kind, 0))
                    .as("no %s questions — that failure mode would go unmeasured", kind)
                    .isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void hasUniqueIds() {
        Set<String> seen = new HashSet<>();
        for (GoldenQuestion q : goldenSet.questions()) {
            assertThat(seen.add(q.id())).as("duplicate question id %s", q.id()).isTrue();
        }
    }

    @Test
    void answerableQuestionsDeclareWhereTheAnswerIs() {
        for (GoldenQuestion q : goldenSet.answerable()) {
            assertThat(q.expected())
                    .as("%s is answerable but names no expected page", q.id())
                    .isNotEmpty();
        }
    }

    @Test
    void unanswerableQuestionsDeclareNothing() {
        // A stray expected page here would be scored as a retrieval failure forever, because the
        // metrics refuse to grade an unanswerable question at all.
        for (GoldenQuestion q : goldenSet.unanswerable()) {
            assertThat(q.expected())
                    .as("%s is marked UNANSWERABLE but names an expected page", q.id())
                    .isEmpty();
        }
        assertThat(goldenSet.unanswerable())
                .as("no unanswerable questions means the confidence gate is unmeasured")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void pagesExistInTheDocumentTheyName() {
        PdfTextExtractor extractor = new PdfTextExtractor();
        Map<FixtureDocument, Integer> pageCounts = new EnumMap<>(FixtureDocument.class);
        for (FixtureDocument document : FixtureDocument.values()) {
            pageCounts.put(document, extractor.extract(document.bytes()).size());
        }

        for (GoldenQuestion q : goldenSet.answerable()) {
            for (PageRef ref : q.expected()) {
                int pages = pageCounts.get(ref.document());
                assertThat(ref.page())
                        .as("%s expects %s but that document has only %d pages", q.id(), ref, pages)
                        .isBetween(1, pages);
            }
        }
    }

    @Test
    void drawsOnMostOfTheCorpusRatherThanOneChapter() {
        // A golden set concentrated in two documents measures those two documents. It would also
        // make retrieval look better than it is, since most of the corpus would never be the right
        // answer to anything and could never be retrieved by mistake.
        Set<FixtureDocument> covered = new HashSet<>();
        goldenSet.answerable().forEach(q -> q.expected().forEach(ref -> covered.add(ref.document())));

        List<FixtureDocument> uncovered = java.util.Arrays.stream(FixtureDocument.values())
                .filter(document -> !covered.contains(document))
                .toList();
        System.out.println("documents no question targets: " + uncovered + "\n");

        assertThat(covered.size())
                .as("golden set only reaches %d of %d documents", covered.size(), FixtureDocument.values().length)
                .isGreaterThanOrEqualTo(FixtureDocument.values().length - 3);
    }
}
