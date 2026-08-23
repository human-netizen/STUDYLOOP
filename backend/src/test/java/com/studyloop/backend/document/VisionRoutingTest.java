package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.config.VisualProperties;
import com.studyloop.backend.document.TestPdfs.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 15.2 and 15.3 — what the router does with the pages the gate condemned.
//
// The measurable claim (does a vision-read corpus retrieve better) belongs to the eval harness and
// needs a real key and a real scan. What is checkable here, in CI and for nothing, is everything
// the eval cannot see: that only the failing pages cost a call, that a routed page's text is
// actually replaced, that the cost ceiling refuses rather than truncates, and that a document with
// no vision key still ingests.
class VisionRoutingTest {

    private static final String MARKDOWN = "# Chapter 4\n\nThe transcribed page.";

    private final FakeVisionClient vision = new FakeVisionClient();

    private PdfExtractionRouter router() {
        return router(VisionProperties.defaults());
    }

    private PdfExtractionRouter router(VisionProperties properties) {
        // The visual selector is handed an embedder that takes no images, which switches it off —
        // this class is about the vision router, and a selector rendering pages underneath it
        // would add cost and noise to every assertion here. VisualChunkingTest is its opposite.
        VisualPageSelector selector = new VisualPageSelector(
                new PageImageRenderer(), VisualProperties.defaults(), new TextOnlyEmbedder());
        return new PdfExtractionRouter(new PdfTextExtractor(), new PageQualityGate(properties),
                new PageImageRenderer(), vision, properties, selector);
    }

    private static final class TextOnlyEmbedder implements EmbeddingClient {

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static VisionProperties withCap(int cap) {
        return new VisionProperties(true, null, null, 0, cap, null, null, 0);
    }

    // ── the ordinary case: nothing to do ────────────────────────────────────────────────────

    @Test
    void aCleanDocumentCostsNoVisionCallAtAll() {
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.PROSE, Kind.PROSE, Kind.BLANK));

        // The whole economic argument of the phase. A digital textbook routes nothing, so the
        // feature is four cheap measurements a page and no provider call — which is what makes it
        // affordable to leave switched on by default.
        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages()).hasSize(3);
        assertThat(extraction.pages().get(0).text()).contains("A skiplist is a sequence");
    }

    @Test
    void switchingTheRouterOffReproducesThePreviousPipelineExactly() {
        VisionProperties off = new VisionProperties(false, "a-key", null, 0, 0, null, null, 0);
        Extraction extraction = router(off).extract(TestPdfs.of(Kind.SCANNED));

        // Not "routes nothing" — scores nothing. A scanned page under an off router is indexed as
        // the empty text PDFBox produced, which is exactly what Phase 14 shipped.
        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages().get(0).text()).isEmpty();
    }

    // ── routing ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aScannedPageIsReplacedByWhatTheModelRead() {
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED, Kind.PROSE));

        assertThat(vision.calls).isEqualTo(1);
        assertThat(extraction.visionPages()).isEqualTo(1);
        // The routed page carries the model's Markdown and its own page number, so a citation to
        // page 2 still opens page 2.
        assertThat(extraction.pages().get(1).pageNumber()).isEqualTo(2);
        assertThat(extraction.pages().get(1).text()).isEqualTo(MARKDOWN);
        // And the pages either side are untouched. A router that rebuilt the whole document from
        // the vision model would be a different, far more expensive feature.
        assertThat(extraction.pages().get(0).text()).contains("A skiplist is a sequence");
        assertThat(extraction.pages().get(2).text()).contains("A skiplist is a sequence");
    }

    @Test
    void onlyTheFailingPagesAreSent() {
        Extraction extraction = router().extract(
                TestPdfs.of(Kind.PROSE, Kind.SCANNED, Kind.PROSE, Kind.FIGURE, Kind.PROSE));

        assertThat(vision.calls).isEqualTo(2);
        assertThat(extraction.visionPages()).isEqualTo(2);
        assertThat(vision.hints).containsExactlyInAnyOrder(PageDefect.SCANNED, PageDefect.FIGURE);
    }

    @Test
    void theDefectTravelsWithThePageSoTheModelIsAskedTheRightThing() {
        router().extract(TestPdfs.of(Kind.TWO_COLUMN));

        // A scanned page needs transcription and a figure page needs a description; sending one
        // prompt for both would waste the one piece of information the gate worked out.
        assertThat(vision.hints).containsExactly(PageDefect.UNRELIABLE_ORDER);
    }

    @Test
    void aBrokenEncodingPageReachesUsableTextThroughTheRouter() {
        // One half of the phase's "done when". The page renders perfectly and extracts as
        // private-use gibberish, so the only route to its content is to look at it.
        Extraction extraction = router().extract(TestPdfs.of(Kind.BROKEN_ENCODING));

        assertThat(extraction.visionPages()).isEqualTo(1);
        assertThat(extraction.pages().get(0).text()).isEqualTo(MARKDOWN);
    }

    @Test
    void everyPageOfAScannedDocumentIsRoutedAndTheDocumentIsWhole() {
        // The other half. Nothing in this document extracted, and all of it comes back.
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.SCANNED, Kind.SCANNED, Kind.SCANNED));

        assertThat(extraction.visionPages()).isEqualTo(3);
        assertThat(extraction.pages()).hasSize(3);
        assertThat(extraction.pages()).allSatisfy(page -> assertThat(page.text()).isEqualTo(MARKDOWN));
    }

    // ── no key ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aDeploymentWithNoVisionKeyKeepsIngestingRatherThanFailingEveryUpload() {
        vision.configured = false;
        Extraction extraction =
                router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED));

        // Refusing here would take documents away from an installation that never opted into this
        // phase. The page keeps the text PDFBox produced — which is nothing — and the router logs
        // at WARN, because a corpus indexed this way is quietly worse at being searched and
        // nothing else would ever say so.
        assertThat(vision.calls).isZero();
        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.pages()).hasSize(2);
    }

    // ── 15.3: the cost ceiling ──────────────────────────────────────────────────────────────

    @Test
    void aDocumentNeedingMoreVisionPagesThanTheCapIsRefusedNotTruncated() {
        assertThatThrownBy(() -> router(withCap(2))
                .extract(TestPdfs.of(Kind.SCANNED, Kind.SCANNED, Kind.SCANNED)))
                .isInstanceOf(VisionPageCapExceededException.class)
                .hasMessageContaining("3 of its 3 pages")
                .hasMessageContaining("limit of 2");

        // Refused *before* spending anything. Routing two pages and then discovering the third is
        // over the line would bill for a document nobody gets.
        assertThat(vision.calls).isZero();
    }

    @Test
    void theCapCountsRoutedPagesRatherThanPages() {
        // A 500-page book with three bad pages is not a 500-page bill, and a cap that counted the
        // document's length would refuse it. What is being bounded is provider calls.
        Extraction extraction = router(withCap(2)).extract(TestPdfs.of(
                Kind.PROSE, Kind.PROSE, Kind.SCANNED, Kind.PROSE, Kind.PROSE, Kind.PROSE));

        assertThat(extraction.visionPages()).isEqualTo(1);
        assertThat(vision.calls).isEqualTo(1);
    }

    // ── failure is loud ─────────────────────────────────────────────────────────────────────

    @Test
    void aVisionFailureFailsTheDocumentRatherThanIndexingTheUnreadablePage() {
        vision.failWith = new VisionExtractionException("the model is down");

        assertThatThrownBy(() -> router().extract(TestPdfs.of(Kind.PROSE, Kind.SCANNED)))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("page 2")
                .hasMessageContaining("scanned");
    }

    @Test
    void anOrdinaryFailureIsNotRetried() {
        // The bounded retry exists for a rate limit, which resolves itself by waiting. Retrying a
        // malformed request three times at twenty-second intervals would turn a failed upload into
        // a failed upload a minute later, and the ingestion executor has other documents queued.
        vision.failWith = new VisionExtractionException("bad request");

        assertThatThrownBy(() -> router().extract(TestPdfs.of(Kind.SCANNED)))
                .isInstanceOf(VisionExtractionException.class);
        assertThat(vision.calls).isEqualTo(1);
    }

    @Test
    void aFailureCarriesTheProvidersOwnMessageForTheUploaderToRead() {
        vision.failWith = new VisionExtractionException("Gemini returned no candidate (SAFETY).");

        assertThatThrownBy(() -> router().extract(TestPdfs.of(Kind.SCANNED)))
                .hasRootCauseMessage("Gemini returned no candidate (SAFETY).");
    }

    // ── the stub ────────────────────────────────────────────────────────────────────────────

    // Records what it was asked as well as how often. The hints matter: "only the failing pages are
    // sent" and "each is sent with the right instruction" are different claims, and a router that
    // got the second wrong would still pass the first.
    private static final class FakeVisionClient implements VisionClient {

        private volatile boolean configured = true;
        private volatile RuntimeException failWith = null;
        private int calls = 0;
        private final List<PageDefect> hints = new ArrayList<>();

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String readPage(byte[] pngImage, PageDefect hint) {
            calls++;
            hints.add(hint);
            // The renderer is the real one, so this also pins that a page actually rasterised:
            // an empty image would mean PDFBox rendering silently produced nothing.
            assertThat(pngImage).isNotEmpty();
            if (failWith != null) {
                throw failWith;
            }
            return MARKDOWN;
        }

        // The PDF router never reads handwriting; HandwrittenNoteReadingTest exercises this half
        // with its own stub. Failing loudly here is deliberate — a router that started calling it
        // would be a routing bug, and a stub that quietly returned something would hide it.
        @Override
        public List<TranscribedBlock> readHandwriting(byte[] image, String mimeType) {
            throw new UnsupportedOperationException(
                    "The PDF router must not read a page as handwriting.");
        }
    }
}
