package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.config.VisionProperties.Thresholds;
import com.studyloop.backend.document.TestPdfs.Kind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 15.1 — four signals, each proved against a page broken in exactly that one way.
//
// The gate is what makes the vision extractor affordable, so what has to be true of it is not
// "it finds bad pages" but both halves of that: it finds each kind of bad page, and it leaves good
// ones alone. The second half is the expensive one to get wrong — a gate that routed 5% of a clean
// textbook would put a provider call on fifteen pages of every upload, forever, for nothing.
class PageQualityGateTest {

    private final PageQualityGate gate = new PageQualityGate(VisionProperties.defaults());

    private List<PageQuality> score(Kind... kinds) throws IOException {
        try (PDDocument document = Loader.loadPDF(TestPdfs.of(kinds))) {
            return gate.score(document);
        }
    }

    private PageQuality scoreOne(Kind kind) throws IOException {
        return score(kind).get(0);
    }

    // ── the control ─────────────────────────────────────────────────────────────────────────

    @Test
    void anOrdinaryPageOfProseIsLeftAlone() throws IOException {
        PageQuality quality = scoreOne(Kind.PROSE);

        assertThat(quality.needsVision()).as("%s", quality.describe()).isFalse();
        assertThat(quality.defect()).isNull();
        // All four measurements, not just the verdict: a page can score clean because every signal
        // agreed it was clean, or because three of them silently returned zero.
        assertThat(quality.charsPerThousandPoints()).isGreaterThan(2.0);
        assertThat(quality.foreignRatio()).isZero();
        assertThat(quality.imageCoverage()).isZero();
        assertThat(quality.orderDisagreement()).isZero();
    }

    @Test
    void aBlankPageIsNotADefect() throws IOException {
        PageQuality quality = scoreOne(Kind.BLANK);

        // "Almost no text" alone would route every chapter opener and every spacer in the corpus,
        // buying a vision call and an empty answer each time. What separates a scan from a blank
        // page is that a scan has an image on it, which is why the low-text rule requires one.
        assertThat(quality.charsPerThousandPoints()).isZero();
        assertThat(quality.needsVision()).isFalse();
    }

    // ── signal 1: the characters were never in the file ─────────────────────────────────────

    @Test
    void aPageFilledByAnImageWithNoTextIsScanned() throws IOException {
        PageQuality quality = scoreOne(Kind.SCANNED);

        assertThat(quality.defect()).isEqualTo(PageDefect.SCANNED);
        assertThat(quality.charsPerThousandPoints()).isZero();
        assertThat(quality.imageCoverage()).isGreaterThan(0.9);
    }

    // ── signal 2: extraction succeeded and returned nonsense ────────────────────────────────

    @Test
    void aBrokenToUnicodeCMapIsCaughtEvenThoughExtractionSucceeded() throws IOException {
        PageQuality quality = scoreOne(Kind.BROKEN_ENCODING);

        // This page yields a full page of characters — the same 4.2 per unit of area as the clean
        // one — which is exactly why no amount of counting text finds it. Only looking at *which*
        // characters came out does.
        assertThat(quality.charsPerThousandPoints()).isGreaterThan(2.0);
        assertThat(quality.foreignRatio()).isGreaterThan(0.9);
        assertThat(quality.defect()).isEqualTo(PageDefect.BROKEN_ENCODING);
    }

    @Test
    void theEncodingSignalSurvivesAPageWithNoLettersOnItAtAll() throws IOException {
        // The bug this pins: the guard that stops three stray characters scoring 100% foreign used
        // to count *letters*, and a page whose CMap is broken has no letters by construction. The
        // check meant to protect the signal switched it off exactly when it mattered.
        PageQuality quality = scoreOne(Kind.BROKEN_ENCODING);

        assertThat(quality.needsVision()).isTrue();
    }

    @Test
    void aCourseWrittenInAnotherScriptIsNotAllDefects() throws IOException {
        // The false positive that would make this signal useless where it is most needed: a Bangla
        // or Greek course under a Latin-only rule has every page flagged. The expected scripts are
        // a property of the corpus, so a corpus that expects nothing but Bengali should read the
        // Latin fixture as foreign — which is the same rule pointing the other way.
        VisionProperties bengali = new VisionProperties(
                true, null, null, 0, 0, List.of("BENGALI"), null);
        PageQuality quality;
        try (PDDocument document = Loader.loadPDF(TestPdfs.of(Kind.PROSE))) {
            quality = new PageQualityGate(bengali).score(document).get(0);
        }

        assertThat(quality.foreignRatio()).isGreaterThan(0.9);
        assertThat(quality.defect()).isEqualTo(PageDefect.BROKEN_ENCODING);
    }

    @Test
    void aScriptListOfNothingButTyposDoesNotRouteTheWholeCorpus() throws IOException {
        // An empty expected-script set makes every character on every page foreign, i.e. sends the
        // entire upload to a vision model because somebody misspelled a configuration value. The
        // names are dropped with a warning and the set falls back to something survivable.
        VisionProperties typos = new VisionProperties(
                true, null, null, 0, 0, List.of("LATNI", "COMMMON"), null);
        try (PDDocument document = Loader.loadPDF(TestPdfs.of(Kind.PROSE))) {
            assertThat(new PageQualityGate(typos).score(document).get(0).needsVision()).isFalse();
        }
    }

    // ── signal 3: the picture is the page ───────────────────────────────────────────────────

    @Test
    void aFigureWithACaptionAroundItIsRoutedEvenThoughItsTextExtractedFine() throws IOException {
        PageQuality quality = scoreOne(Kind.FIGURE);

        assertThat(quality.defect()).isEqualTo(PageDefect.FIGURE);
        // Nothing is wrong with this page's text. That is the point: figure questions have been the
        // weakest kind in the golden set since Phase 12, and Phase 14 established that no amount of
        // generated *text* touches a question about a plot.
        assertThat(quality.charsPerThousandPoints()).isGreaterThan(0.5);
        assertThat(quality.imageCoverage()).isBetween(0.25, 0.4);
    }

    @Test
    void aFigureThatWasDrawnRatherThanPastedIsAlsoAFigure() throws IOException {
        PageQuality quality = scoreOne(Kind.VECTOR_FIGURE);

        // The gap the plan's signal left, and the corpus is the evidence for it: the fourteen
        // fixture chapters contain zero image XObjects across all 306 pages, because a book
        // typeset in LaTeX draws its diagrams. Measuring only pasted pictures would have made the
        // FIGURE verdict unreachable on the exact material it was written for.
        assertThat(quality.imageCoverage()).isZero();
        assertThat(quality.vectorSegments()).isGreaterThan(150);
        assertThat(quality.defect()).isEqualTo(PageDefect.FIGURE);
    }

    @Test
    void aRuleUnderAHeadingIsNotAFigure() throws IOException {
        // The other side of the same threshold. A page of prose draws a handful of segments —
        // underlines, table rules, the line under a running head — and a count that treated those
        // as a diagram would route most of a book.
        PageQuality quality = scoreOne(Kind.PROSE);

        assertThat(quality.vectorSegments()).isLessThan(150);
        assertThat(quality.needsVision()).isFalse();
    }

    // ── signal 4: the words are right and the order is not ──────────────────────────────────

    @Test
    void aTwoColumnPageIsCaughtByTheDisagreementBetweenTheTwoReadings() throws IOException {
        PageQuality quality = scoreOne(Kind.TWO_COLUMN);

        assertThat(quality.defect()).isEqualTo(PageDefect.UNRELIABLE_ORDER);
        // Both passes read the same words; only the sequence differs. Sorting by position reads
        // across the gutter, the content stream reads down each column, and neither is knowably
        // right — which is also why plain OCR is not the fallback for this page.
        //
        // Just under a half is the ceiling this measure has for two columns, not a weak reading:
        // the longest order both readings agree on is essentially one whole column, so the other
        // column is what the score counts. It measures 0.44 against 0.00 for the prose page above,
        // which is the separation the 0.35 threshold sits in.
        assertThat(quality.orderDisagreement()).isBetween(0.4, 0.5);
        assertThat(quality.foreignRatio()).isZero();
    }

    // ── the verdict, and the document as a whole ────────────────────────────────────────────

    @Test
    void everyPageIsScoredAndTheyKeepTheirPageNumbers() throws IOException {
        List<PageQuality> qualities =
                score(Kind.PROSE, Kind.SCANNED, Kind.PROSE, Kind.FIGURE, Kind.BLANK);

        // Page numbers are what the router routes by and what a citation opens, so an off-by-one
        // here would rewrite the wrong page with somebody else's content.
        assertThat(qualities).hasSize(5);
        assertThat(qualities.stream().map(PageQuality::pageNumber)).containsExactly(1, 2, 3, 4, 5);
        assertThat(PageQualityGate.pagesNeedingVision(qualities)).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    void theWorstDefectIsReportedRatherThanAllOfThem() throws IOException {
        // A scanned page is also a page with full image coverage and no reading order. Reporting
        // every box it ticks would say nothing about what to do with it; the verdict names the
        // reason it is unusable, and SCANNED is the reason.
        PageQuality quality = scoreOne(Kind.SCANNED);

        assertThat(quality.imageCoverage()).isGreaterThan(0.25);
        assertThat(quality.defect()).isEqualTo(PageDefect.SCANNED);
    }

    @Test
    void thresholdsAreConfigurationSoTheRouterCanBeArguedWith() throws IOException {
        // Raising the figure-coverage cut above what the fixture has must stop routing it. This is
        // not a test of arithmetic — it is what makes the fixture report in FixtureCorpusTest an
        // argument rather than an assertion: a reader who thinks 0.25 is wrong can move it.
        VisionProperties strict = new VisionProperties(true, null, null, 0, 0, null,
                new Thresholds(0, 0, 0.95, 100_000, 0, 0, 0));
        try (PDDocument document = Loader.loadPDF(TestPdfs.of(Kind.FIGURE))) {
            assertThat(new PageQualityGate(strict).score(document).get(0).needsVision()).isFalse();
        }
    }
}
