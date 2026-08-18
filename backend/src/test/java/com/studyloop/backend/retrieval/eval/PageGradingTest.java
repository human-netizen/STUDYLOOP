package com.studyloop.backend.retrieval.eval;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The relevance rule, tested apart from the arithmetic that consumes it. These are the cases that
// would otherwise silently corrupt every metric the harness prints.
class PageGradingTest {

    @Test
    void creditsAnExactPageMatch() {
        assertThat(PageGrading.grade(List.of(23), List.of(23))).containsExactly(1.0);
    }

    @Test
    void creditsAChunkThatStartedOnTheNeighbouringPage() {
        // A chunk beginning on page 22 can carry text that reads as page 23.
        assertThat(PageGrading.grade(List.of(22), List.of(23))).containsExactly(1.0);
        assertThat(PageGrading.grade(List.of(24), List.of(23))).containsExactly(1.0);
    }

    @Test
    void refusesAPageOutsideTolerance() {
        assertThat(PageGrading.grade(List.of(25), List.of(23))).containsExactly(0.0);
    }

    @Test
    void honoursAStricterTolerance() {
        assertThat(PageGrading.grade(List.of(22), List.of(23), 0)).containsExactly(0.0);
        assertThat(PageGrading.grade(List.of(23), List.of(23), 0)).containsExactly(1.0);
    }

    @Test
    void creditsEachExpectedPageOnlyOnce() {
        // Retrieval routinely returns several chunks off the same page. Counting each as a fresh
        // hit would push DCG above its own ideal and produce an nDCG greater than 1.
        List<Double> graded = PageGrading.grade(List.of(23, 23, 23), List.of(23));
        assertThat(graded).containsExactly(1.0, 0.0, 0.0);
    }

    @Test
    void creditsEveryDistinctExpectedPageThatWasFound() {
        List<Double> graded = PageGrading.grade(List.of(23, 40, 24), List.of(23, 24));
        assertThat(graded).containsExactly(1.0, 0.0, 1.0);
    }

    @Test
    void spendsOneRetrievedPageOnOneExpectedPage() {
        // Page 24 sits within tolerance of both 23 and 25. It may satisfy one of them, not both,
        // otherwise a single lucky chunk would score as full recall of a three-page answer.
        List<Double> graded = PageGrading.grade(List.of(24), List.of(23, 25));
        assertThat(graded).containsExactly(1.0);
        assertThat(RetrievalMetrics.recallAt(graded, 2)).isEqualTo(0.5);
    }

    @Test
    void gradesAChunkWithNoPageAsAMiss() {
        // Forum-derived documents were never in a PDF, so their chunks have no page — but they
        // still occupied a slot in the top-k that a real page could have taken.
        List<Double> graded = PageGrading.grade(Arrays.asList(null, 23), List.of(23));
        assertThat(graded).containsExactly(0.0, 1.0);
    }

    @Test
    void doesNotCreditTheRightPageOfTheWrongDocument() {
        // The failure this exists to catch: every chapter has a page 4, and without the document
        // in the key a chunk from the sorting lecture would satisfy a question about hashing.
        List<PageRef> expected = List.of(PageRef.of(FixtureDocument.HASH_TABLES, 4));
        List<PageRef> wrongDocument = List.of(PageRef.of(FixtureDocument.SORTING_ALGORITHMS, 4));
        List<PageRef> rightDocument = List.of(PageRef.of(FixtureDocument.HASH_TABLES, 4));

        assertThat(PageGrading.gradeRefs(wrongDocument, expected)).containsExactly(0.0);
        assertThat(PageGrading.gradeRefs(rightDocument, expected)).containsExactly(1.0);
    }

    @Test
    void appliesToleranceWithinADocumentOnly() {
        List<PageRef> expected = List.of(PageRef.of(FixtureDocument.HEAPS, 5));
        assertThat(PageGrading.gradeRefs(List.of(PageRef.of(FixtureDocument.HEAPS, 6)), expected))
                .containsExactly(1.0);
        assertThat(PageGrading.gradeRefs(List.of(PageRef.of(FixtureDocument.GRAPHS, 6)), expected))
                .containsExactly(0.0);
    }

    @Test
    void gradesAnAnswerThatSpansTwoDocuments() {
        // Cross-section synthesis questions expect pages from more than one lecture; each is
        // credited once, so finding both scores full recall and finding one scores half.
        List<PageRef> expected = List.of(
                PageRef.of(FixtureDocument.SKIPLISTS, 12),
                PageRef.of(FixtureDocument.RED_BLACK_TREES, 21));

        List<Double> both = PageGrading.gradeRefs(List.of(
                PageRef.of(FixtureDocument.SKIPLISTS, 12),
                PageRef.of(FixtureDocument.RED_BLACK_TREES, 21)), expected);
        assertThat(RetrievalMetrics.recallAt(both, 2)).isEqualTo(1.0);

        List<Double> one = PageGrading.gradeRefs(List.of(
                PageRef.of(FixtureDocument.SKIPLISTS, 12),
                PageRef.of(FixtureDocument.SKIPLISTS, 12)), expected);
        assertThat(RetrievalMetrics.recallAt(one, 2)).isEqualTo(0.5);
    }

    // ── page spans (Phase 13) ───────────────────────────────────────────────────────────────────

    @Test
    void aChunkIsCreditedForAnyPageItCovers() {
        // The straddle case, which is why the ±1 tolerance existed: a section running from 12 to 13
        // holds an answer printed on 13, and the chunk's stored page is 12.
        List<PageSpan> retrieved = List.of(PageSpan.of(FixtureDocument.SKIPLISTS, 12, 13));

        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 13)), 0))
                .containsExactly(1.0);
        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 12)), 0))
                .containsExactly(1.0);
    }

    @Test
    void aChunkIsNotCreditedForThePageAfterItEnds() {
        // What the tolerance was quietly buying, and the reason removing it is a result rather than
        // a tightening: under ±1 this scored 1.0, and the chunk does not contain the answer.
        List<PageSpan> retrieved = List.of(PageSpan.of(FixtureDocument.SKIPLISTS, 12, 13));

        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 14)), 0))
                .containsExactly(0.0);
    }

    @Test
    void aSpanWithNoEndCoversThePageItNames() {
        // Chunks written before Phase 13 record no page_end. They keep grading exactly as they did.
        List<PageSpan> retrieved = List.of(PageSpan.of(FixtureDocument.SKIPLISTS, 12, null));

        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 12)), 0))
                .containsExactly(1.0);
        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 13)), 0))
                .containsExactly(0.0);
    }

    @Test
    void aLongSpanStillOnlyClaimsOneExpectedPage() {
        // Otherwise one chapter-sized chunk would score full recall on a synthesis question by
        // covering both of its pages, which is the opposite of what those questions measure.
        List<PageSpan> retrieved = List.of(PageSpan.of(FixtureDocument.SKIPLISTS, 10, 20));
        List<PageRef> expected = List.of(
                PageRef.of(FixtureDocument.SKIPLISTS, 12),
                PageRef.of(FixtureDocument.SKIPLISTS, 18));

        assertThat(RetrievalMetrics.recallAt(PageGrading.gradeSpans(retrieved, expected, 0), 2))
                .isEqualTo(0.5);
    }

    @Test
    void aSpanInAnotherDocumentIsNeverCredited() {
        List<PageSpan> retrieved = List.of(PageSpan.of(FixtureDocument.HEAPS, 12, 13));

        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 12)), 0))
                .containsExactly(0.0);
    }

    @Test
    void aNullSpanStillConsumesItsSlot() {
        // A forum answer has no page. Dropping it would flatter the run: it occupied one of the six
        // places a relevant chunk could have had.
        List<PageSpan> retrieved = Arrays.asList(null, PageSpan.of(FixtureDocument.SKIPLISTS, 12, 12));

        assertThat(PageGrading.gradeSpans(retrieved, List.of(PageRef.of(FixtureDocument.SKIPLISTS, 12)), 0))
                .containsExactly(0.0, 1.0);
    }

    @Test
    void producesGainsInRetrievalOrderSoRankIsPreserved() {
        List<Double> graded = PageGrading.grade(List.of(99, 98, 23), List.of(23));
        assertThat(RetrievalMetrics.reciprocalRank(graded)).isCloseTo(1.0 / 3, org.assertj.core.api.Assertions.within(1e-6));
    }
}
