package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 16.2 — reading a Word document as Markdown.
//
// The reason this is nearly free once 16.1 exists is also the reason it is *better* than a PDF:
// `Heading 1` is a style the author applied, stored under that name in the file. Phase 13.2 has to
// infer headings in a PDF from font size and position and gets them mostly right; here there is
// nothing to infer. So the tests below are about faithfully translating a declaration, and about
// the one thing a .docx genuinely does not have — pages.
class DocxExtractorTest {

    private final DocxExtractor extractor = new DocxExtractor();

    @Test
    void wordHeadingStylesBecomeMarkdownHeadingsAtTheSameDepth() {
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "Sorting")
                .paragraph("Comparison sorts have a lower bound.")
                .heading("Heading2", "Quicksort")
                .paragraph("Expected O(n log n).")
                .heading("Heading3", "Pivot selection")
                .paragraph("Median of three.")
                .bytes();

        String markdown = extractor.extract(docx).pages().get(0).text();

        assertThat(markdown).contains("# Sorting");
        assertThat(markdown).contains("## Quicksort");
        assertThat(markdown).contains("### Pivot selection");
    }

    @Test
    void theHeadingHierarchyIsWhatTheChunkerSeesAsSectionPaths() {
        // The same argument as the slide test: the value of a heading is that tier 1 cuts on it.
        // A .docx therefore arrives at the chunker better structured than a PDF of the same text,
        // because nothing had to guess where a section began.
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "Graphs")
                .heading("Heading2", "Traversal")
                .paragraph("Breadth first visits by distance.")
                .heading("Heading2", "Shortest paths")
                .paragraph("Dijkstra needs non-negative weights.")
                .bytes();

        List<SectionBlock> blocks = new StructuralSplitter().split(extractor.extract(docx).pages());

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).pathLabel()).isEqualTo("Graphs > Traversal");
        // Popped by level, not by depth: the second H2 is a sibling of the first, not its child.
        assertThat(blocks.get(1).pathLabel()).isEqualTo("Graphs > Shortest paths");
    }

    @Test
    void theTitleStyleIsTheTopLevelHeading() {
        byte[] docx = TestOfficeFiles.doc()
                .heading("Title", "Lecture Notes: Week 4")
                .paragraph("Everything below belongs under it.")
                .bytes();

        assertThat(extractor.extract(docx).pages().get(0).text())
                .startsWith("# Lecture Notes: Week 4");
    }

    @Test
    void wordsSeventhHeadingLevelIsClampedToWhatMarkdownCanExpress() {
        // Word allows nine levels and Markdown has six. Dropping the structure entirely would make
        // the text a paragraph of the level above it; the deepest heading Markdown has is the
        // closest true statement.
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading7", "A very deeply nested point")
                .paragraph("Body.")
                .bytes();

        assertThat(extractor.extract(docx).pages().get(0).text())
                .contains("###### A very deeply nested point");
    }

    @Test
    void listItemsBecomeMarkdownListItemsAtTheirIndentLevel() {
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "Invariants")
                .listItem("The heap property holds", 0)
                .listItem("Except during sift-down", 1)
                .bytes();

        String markdown = extractor.extract(docx).pages().get(0).text();

        assertThat(markdown).contains("- The heap property holds");
        assertThat(markdown).contains("  - Except during sift-down");
    }

    @Test
    void aWordTableComesBackAsAMarkdownTable() {
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "Complexity")
                .table(List.of(
                        List.of("Structure", "Find"),
                        List.of("Skiplist", "O(log n)")))
                .bytes();

        String markdown = extractor.extract(docx).pages().get(0).text();

        assertThat(markdown).contains("| Structure | Find |");
        assertThat(markdown).contains("| --- | --- |");
        assertThat(markdown).contains("| Skiplist | O(log n) |");
    }

    @Test
    void aDocumentWithNoPageBreaksIsOnePage() {
        // Not a shortcut. Word computes page breaks when it lays a document out for a paper size
        // and a font; the file carries only the breaks the author forced. Numbering sections and
        // calling them pages would put "page 4" on a citation for something Word prints on 11.
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "One")
                .paragraph("First.")
                .heading("Heading1", "Two")
                .paragraph("Second.")
                .bytes();

        List<PageText> pages = extractor.extract(docx).pages();

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    void anAuthoredPageBreakStartsANewPage() {
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "Chapter One")
                .paragraph("Before the break.")
                .pageBreak()
                .heading("Heading1", "Chapter Two")
                .paragraph("After the break.")
                .bytes();

        List<PageText> pages = extractor.extract(docx).pages();

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).text()).contains("Before the break.");
        assertThat(pages.get(1).text()).contains("After the break.");
        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
    }

    @Test
    void consecutivePageBreaksDoNotLeaveEmptyPagesBehind() {
        // Three breaks in a row is one break to a reader — usually somebody pressing Ctrl+Enter to
        // push a heading down. Keeping the empty pages would push every citation after them two
        // pages further on than the document reads.
        byte[] docx = TestOfficeFiles.doc()
                .paragraph("First page.")
                .pageBreak()
                .pageBreak()
                .pageBreak()
                .paragraph("Second page.")
                .bytes();

        List<PageText> pages = extractor.extract(docx).pages();

        assertThat(pages).hasSize(2);
        assertThat(pages.get(1).text()).contains("Second page.");
    }

    @Test
    void aDocumentWithNoTextIsAFailureRatherThanAnEmptyDocument() {
        byte[] docx = TestOfficeFiles.doc().bytes();

        assertThatThrownBy(() -> extractor.extract(docx))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("no text in it");
    }

    @Test
    void bytesThatAreNotAWordDocumentFailWithSomethingTheUploaderCanActOn() {
        byte[] nonsense = "this is not a Word document".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(nonsense))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("older Word format");
    }

    @Test
    void theRouterClaimsDocxAndNothingElse() {
        assertThat(extractor.supports(DocumentFormat.DOCX)).isTrue();
        assertThat(extractor.supports(DocumentFormat.PPTX)).isFalse();
        assertThat(extractor.supports(DocumentFormat.PDF)).isFalse();
    }
}
