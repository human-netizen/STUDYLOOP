package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.config.VisualProperties;
import com.studyloop.backend.document.TestPdfs.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 17.2 — which pages become pictures, and what the chunk made from one carries.
//
// No Spring, no database, no provider: the selector reads numbers the quality gate already
// computed and PDFBox renders its own file, so the whole decision is checkable in CI for nothing.
// What is *not* checkable here is whether embed-v4.0 is any good at pages, which is a judgement
// about a provider — that belongs to the eval harness and a real key.
class VisualChunkingTest {

    private static final String TITLE = "Lecture 4 — Skiplists";

    private final VisualChunker chunker = new VisualChunker(new TokenCounter());

    private VisualPageSelector selector() {
        return selector(VisualProperties.defaults());
    }

    private VisualPageSelector selector(VisualProperties properties) {
        return new VisualPageSelector(new PageImageRenderer(), properties, new ImageEmbedder(true));
    }

    private List<PageImage> imagesOf(VisualPageSelector selector, byte[] pdf) {
        return PdfFixtures.withDocument(pdf, document ->
                selector.select(document, new PageQualityGate(VisionProperties.defaults())
                        .score(document)));
    }

    // ── which pages ─────────────────────────────────────────────────────────────────────────

    @Test
    void aPageOfProseIsNotAPicture() {
        // The control, and the cost argument. A textbook chapter of plain prose renders nothing
        // and embeds nothing, so a course that uploaded no diagrams pays for none.
        assertThat(imagesOf(selector(), TestPdfs.of(Kind.PROSE, Kind.PROSE, Kind.BLANK))).isEmpty();
    }

    @Test
    void aPastedFigureAndADrawnOneBothCount() {
        List<PageImage> images = imagesOf(selector(),
                TestPdfs.of(Kind.PROSE, Kind.FIGURE, Kind.VECTOR_FIGURE, Kind.PROSE));

        // Two ways to put a picture on a page and both have to be seen. The drawn one is the case
        // that matters for this corpus: Open Data Structures has zero image XObjects across all
        // 306 of its pages and a diagram on dozens of them, because a LaTeX book draws its figures.
        assertThat(images).extracting(PageImage::pageNumber).containsExactly(2, 3);
        assertThat(images.get(0).imageCoverage()).isGreaterThan(0.15);
        assertThat(images.get(1).imageCoverage()).isZero();
        assertThat(images.get(1).vectorSegments()).isGreaterThanOrEqualTo(150);
    }

    @Test
    void aDenseTextPageWithADiagramIsAPictureHereAndNotToTheVisionRouter() {
        byte[] pdf = TestPdfs.of(Kind.DENSE_FIGURE);

        // This one assertion is the reason the phase exists as more than a caller change. The
        // Phase 15 gate declines this page — its text extracted perfectly, which was the question
        // it was asked — and so the diagram on it has never been reachable by anything. Phase 17
        // asks the other question about the same measurements and takes it.
        List<PageQuality> qualities = PdfFixtures.withDocument(pdf, document ->
                new PageQualityGate(VisionProperties.defaults()).score(document));
        assertThat(qualities.get(0).needsVision())
                .as("the vision router should still leave a well-extracted page alone")
                .isFalse();

        assertThat(imagesOf(selector(), pdf)).extracting(PageImage::pageNumber).containsExactly(1);
    }

    @Test
    void switchingItOffReproducesThePhase16Corpus() {
        VisualProperties off = new VisualProperties(false, 0, 0, 0, 0);
        assertThat(imagesOf(selector(off), TestPdfs.of(Kind.VECTOR_FIGURE))).isEmpty();
    }

    @Test
    void anEmbedderThatCannotTakeImagesRendersNothing() {
        VisualPageSelector selector = new VisualPageSelector(
                new PageImageRenderer(), VisualProperties.defaults(), new ImageEmbedder(false));

        // Not "renders them and stores no vector". A visual chunk with a null embedding is a second
        // copy of its page's text that no query can reach, sitting in the table beside the chunks
        // that can — so a deployment on a text-only embedder writes none of them at all.
        assertThat(selector.enabled()).isFalse();
        assertThat(imagesOf(selector, TestPdfs.of(Kind.VECTOR_FIGURE))).isEmpty();
    }

    @Test
    void theCapKeepsTheBusiestPagesRatherThanTheFirstOnes() {
        VisualProperties capped = new VisualProperties(true, 0, 2, 0, 0);
        // Three drawn figures and one pasted one. The pasted page covers a quarter of the sheet, so
        // it outranks the drawn pages on coverage; the cap has to keep it even though it is last.
        List<PageImage> images = imagesOf(selector(capped),
                TestPdfs.of(Kind.VECTOR_FIGURE, Kind.VECTOR_FIGURE, Kind.VECTOR_FIGURE, Kind.FIGURE));

        assertThat(images).hasSize(2);
        assertThat(images).extracting(PageImage::pageNumber).contains(4);
        // Restored to page order, because the chunk indices assigned next have to run forward.
        assertThat(images.get(0).pageNumber()).isLessThan(images.get(1).pageNumber());
    }

    // ── what the chunk carries ──────────────────────────────────────────────────────────────

    @Test
    void theTextHalfIsThePagesOwnTextUnderAHeadingThatNamesThePage() {
        List<PageText> pages = List.of(
                new PageText(1, "Ordinary prose."),
                new PageText(2, "Figure 4.2: a skiplist with four levels."));
        List<VisualChunk> chunks = chunker.chunk(
                List.of(new PageImage(2, new byte[]{1, 2, 3}, 200, 0.0)), pages, TITLE, 7);

        assertThat(chunks).hasSize(1);
        // Command R is text-only, so a chunk retrieved by its picture has to arrive at the
        // generator as words. These are the words that page has.
        assertThat(chunks.get(0).content())
                .startsWith("Lecture 4 — Skiplists — page 2")
                .contains("Figure 4.2: a skiplist with four levels.");
        assertThat(chunks.get(0).tokenCount()).isPositive();
    }

    @Test
    void aPageWithNoTextAtAllStillBecomesAChunk() {
        List<VisualChunk> chunks = chunker.chunk(
                List.of(new PageImage(3, new byte[]{9}, 400, 0.0)),
                List.of(new PageText(3, "   ")), TITLE, 0);

        // The one kind of page this phase exists for is a figure with nothing written near it.
        // Dropping it for having no text would make that page the one page it cannot index.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("Lecture 4 — Skiplists — page 3");
    }

    @Test
    void visualChunksContinueTheDocumentsIndexSequence() {
        List<VisualChunk> chunks = chunker.chunk(
                List.of(new PageImage(1, new byte[]{1}, 200, 0.0),
                        new PageImage(4, new byte[]{2}, 200, 0.0)),
                List.of(new PageText(1, "one"), new PageText(4, "four")), TITLE, 12);

        // The (document, chunk_index) unique constraint predates this phase and still has to mean
        // something, so visual chunks are appended after the text chunks rather than renumbered
        // alongside them.
        assertThat(chunks).extracting(VisualChunk::index).containsExactly(12, 13);
        assertThat(chunks).extracting(VisualChunk::pageNumber).containsExactly(1, 4);
    }

    // An embedder that says whether it takes pictures and does nothing else. The selector reads
    // only that answer, so anything more would be a fixture pretending to be a provider.
    private record ImageEmbedder(boolean images) implements EmbeddingClient {

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean supportsImages() {
            return images;
        }
    }
}
