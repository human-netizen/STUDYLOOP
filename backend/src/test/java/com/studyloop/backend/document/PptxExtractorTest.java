package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 16.1 — reading a PowerPoint deck as Markdown pages.
//
// Every test here is about a property of the *deck* surviving into the Markdown, because that is
// the whole argument for reading the .pptx instead of a PDF export of it: the structure is in the
// file, and an export throws it away. So the assertions are "the title became a heading", "the
// speaker notes came through and are labelled", "the second column was read second" — not "the
// text is in there somewhere", which a PDF export would also pass.
class PptxExtractorTest {

    private final PptxExtractor extractor = new PptxExtractor();

    @Test
    void aSlideTitleBecomesAHeadingAndTheSlideNumberBecomesThePageNumber() {
        byte[] deck = TestOfficeFiles.deck()
                .slide("Amortized Analysis", List.of("Cost per operation", "Not worst case"), null)
                .slide("Skiplists", List.of("Expected O(log n)"), null)
                .bytes();

        List<PageText> pages = extractor.extract(deck).pages();

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
        // The number rides in the heading as well as in the page field, because a deck reuses its
        // titles — "Example", "Example", "Summary" — and a section path has to name one slide.
        assertThat(pages.get(0).text()).startsWith("# Slide 1: Amortized Analysis");
        assertThat(pages.get(1).text()).startsWith("# Slide 2: Skiplists");
    }

    @Test
    void theHeadingIsWhatMakesASlideATierOneChunkingUnit() {
        // The point of the heading is not formatting. `StructuralSplitter` is the chunker's first
        // tier and it cuts on Markdown headings; a deck exported to PDF has none, which is exactly
        // why 13.3 needed a semantic tier for slides at all. Reading the .pptx gives tier 1 real
        // boundaries, so this asserts the chunker's own view rather than the extractor's output.
        byte[] deck = TestOfficeFiles.deck()
                .slide("Hash Tables", List.of("Chaining", "Open addressing"), null)
                .slide("Collision Resolution", List.of("Linear probing"), null)
                .bytes();

        List<SectionBlock> blocks = new StructuralSplitter().split(extractor.extract(deck).pages());

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).pathLabel()).isEqualTo("Slide 1: Hash Tables");
        assertThat(blocks.get(1).pathLabel()).isEqualTo("Slide 2: Collision Resolution");
        assertThat(blocks.get(0).pageStart()).isEqualTo(1);
        assertThat(blocks.get(1).pageStart()).isEqualTo(2);
    }

    @Test
    void bulletsStayBullets() {
        byte[] deck = TestOfficeFiles.deck()
                .slide("Three Cases", List.of("Base case", "Recursive case", "Degenerate case"), null)
                .bytes();

        String markdown = extractor.extract(deck).pages().get(0).text();

        // A list of things is how a slide states a list of things, and a retriever matching "the
        // three cases" should find the slide that has three of them rather than one paragraph.
        assertThat(markdown).contains("- Base case");
        assertThat(markdown).contains("- Recursive case");
        assertThat(markdown).contains("- Degenerate case");
    }

    @Test
    void theSpeakerNotesAreTakenAndAreMarkedAsSpeakerNotes() {
        byte[] deck = TestOfficeFiles.deck()
                .slide("Amortized Analysis", List.of("O(1) per operation"),
                        "The trick is that the expensive resize pays for the cheap appends "
                                + "that came before it.")
                .bytes();

        String markdown = extractor.extract(deck).pages().get(0).text();

        // Taken, because the slide is a phrase and the notes are the sentence that explains it —
        // dropping them throws away the only prose in most decks.
        assertThat(markdown).contains("the expensive resize pays for the cheap appends");
        // Marked, because they are the lecturer talking rather than the material. The `##` puts
        // "Speaker notes" into the section path, which is what 13.4 prepends to the indexed text,
        // so an answer model can tell an aside from a definition.
        assertThat(markdown).contains("## Speaker notes");
        assertThat(markdown.indexOf("## Speaker notes"))
                .isGreaterThan(markdown.indexOf("O(1) per operation"));
    }

    @Test
    void aSlideWithNoNotesGetsNoNotesHeading() {
        byte[] deck = TestOfficeFiles.deck()
                .slide("Skiplists", List.of("Expected O(log n)"), null)
                .bytes();

        assertThat(extractor.extract(deck).pages().get(0).text()).doesNotContain("Speaker notes");
    }

    @Test
    void theSlideTitleIsNotWrittenTwiceWhenTheDeckHasNotes() {
        // The notes page carries a thumbnail of the slide, whose title placeholder repeats the
        // title. Left in, every slide with notes would open by saying its own name twice.
        byte[] deck = TestOfficeFiles.deck()
                .slide("Red-Black Trees", List.of("Every path has the same black height"),
                        "Draw the 2-4 tree next to it.")
                .bytes();

        String markdown = extractor.extract(deck).pages().get(0).text();

        assertThat(markdown.split("Red-Black Trees", -1)).hasSize(2);
    }

    @Test
    void anUntitledSlideStillGetsAHeading() {
        // Otherwise a deck of untitled slides has no structure at all and falls through to the
        // semantic splitter — one embedding pass to rediscover boundaries the file already has.
        // "Slide 1" is a true, unique, page-numbered boundary, which is what tier 1 needs.
        byte[] deck = TestOfficeFiles.deck().untitled("Any questions?").bytes();

        String markdown = extractor.extract(deck).pages().get(0).text();

        assertThat(markdown).startsWith("# Slide 1\n");
        assertThat(markdown).contains("Any questions?");
    }

    @Test
    void shapesAreReadInReadingOrderRatherThanInPaintOrder() {
        // `getShapes()` is z-order, which is paint order: PowerPoint returns the box that was
        // created first, and authors do not create left to right. A deck read in paint order gives
        // the chunker a slide whose two columns are interleaved backwards.
        byte[] deck = TestOfficeFiles.deck()
                .twoColumns("left column text", "right column text")
                .bytes();

        String markdown = extractor.extract(deck).pages().get(0).text();

        assertThat(markdown.indexOf("left column text"))
                .isLessThan(markdown.indexOf("right column text"));
    }

    @Test
    void aTableOnASlideComesBackAsAMarkdownTable() {
        byte[] deck = TestOfficeFiles.deck()
                .tableSlide("Complexity", List.of(
                        List.of("Operation", "Array", "List"),
                        List.of("Insert", "O(n)", "O(1)")))
                .bytes();

        String markdown = extractor.extract(deck).pages().get(0).text();

        assertThat(markdown).contains("| Operation | Array | List |");
        assertThat(markdown).contains("| --- | --- | --- |");
        assertThat(markdown).contains("| Insert | O(n) | O(1) |");
    }

    @Test
    void aDeckWithNoSlidesIsAFailureRatherThanAnEmptyDocument() {
        byte[] empty = TestOfficeFiles.deck().bytes();

        assertThatThrownBy(() -> extractor.extract(empty))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("no slides");
    }

    @Test
    void bytesThatAreNotAPresentationFailWithSomethingTheUploaderCanAct0n() {
        byte[] nonsense = "this is not a presentation".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(nonsense))
                .isInstanceOf(DocumentExtractionException.class)
                // The pre-2007 case is the likely one and the message names it, because "could not
                // read the presentation" leaves the uploader with nothing to do about it.
                .hasMessageContaining("older PowerPoint format");
    }

    @Test
    void theRouterClaimsPptxAndNothingElse() {
        assertThat(extractor.supports(DocumentFormat.PPTX)).isTrue();
        assertThat(extractor.supports(DocumentFormat.PDF)).isFalse();
        assertThat(extractor.supports(DocumentFormat.DOCX)).isFalse();
        assertThat(extractor.supports(DocumentFormat.PNG)).isFalse();
    }

    @Test
    void aDeckCostsNoVisionCallsAndProducesNoReviewableBlocks() {
        // The two fields Phase 15 and 16.3 added to Extraction. A .pptx is read locally, so both
        // are empty — and `visionPages` staying zero is what keeps the eval report's
        // "N/M pages read by a vision model" line true when a course uploads slides.
        Extraction extraction = extractor.extract(TestOfficeFiles.deck()
                .slide("Graphs", List.of("Adjacency list"), null)
                .bytes());

        assertThat(extraction.visionPages()).isZero();
        assertThat(extraction.blocks()).isEmpty();
    }
}
