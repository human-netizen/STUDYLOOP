package com.studyloop.backend.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 13.2 — extraction produces Markdown, so the chunker has structure to cut on.
//
// Every fixture here is a PDF built in the test, because that is the only way to control the one
// thing under test: what size each line was set in. A PDF records no headings, so "this line is a
// heading" is a claim about typography, and a test that asserts it has to be able to typeset.
class MarkdownExtractionTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    private record Line(String text, float size, boolean bold) { }

    private static Line body(String text) {
        return new Line(text, 11, false);
    }

    private static Line heading(String text, float size) {
        return new Line(text, size, false);
    }

    // One page per list. Lines are laid out top to bottom with leading proportional to their size,
    // which is what a real typesetter does and what the paragraph-gap rule reads.
    private static byte[] pdf(List<List<Line>> pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (List<Line> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                float y = 740;
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    for (Line line : lines) {
                        content.beginText();
                        content.setFont(new PDType1Font(line.bold()
                                ? Standard14Fonts.FontName.HELVETICA_BOLD
                                : Standard14Fonts.FontName.HELVETICA), line.size());
                        content.newLineAtOffset(72, y);
                        content.showText(line.text());
                        content.endText();
                        y -= line.size() * 1.4f;
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private String extract(List<List<Line>> pages) throws IOException {
        List<PageText> extracted = extractor.extract(pdf(pages));
        return String.join("\n\n", extracted.stream().map(PageText::text).toList());
    }

    @Test
    void aLineSetLargerThanTheBodyBecomesAHeading() throws IOException {
        String markdown = extract(List.of(List.of(
                heading("Skiplists", 20),
                body("A skiplist is a sequence of singly linked lists."),
                body("Each list contains a subset of the one below it."))));

        assertThat(markdown).contains("# Skiplists");
        assertThat(markdown).contains("A skiplist is a sequence");
    }

    @Test
    void headingLevelsComeFromTheDocumentsOwnSizesNotFromAPointTable() throws IOException {
        // Two heading sizes, so two levels — and the levels are assigned by rank. A document that
        // set its chapter title at 20pt and another that set it at 34pt both get `#`, which is what
        // makes this work on material nobody typeset for us.
        String markdown = extract(List.of(List.of(
                heading("Skiplists", 20),
                body("This chapter is about skiplists and their analysis."),
                heading("4.1 The Basic Structure", 14),
                body("Conceptually a skiplist is a sequence of lists."))));

        assertThat(markdown).contains("# Skiplists");
        assertThat(markdown).contains("## 4.1 The Basic Structure");
    }

    @Test
    void aBoldNumberedLineIsAHeadingEvenAtBodySize() throws IOException {
        // The fallback for books that mark subsections bold without enlarging them. Bold alone is
        // never enough — half the terms in a textbook are bold — so it only counts with a number.
        String markdown = extract(List.of(List.of(
                heading("Hash Tables", 20),
                body("Hashing stores elements in an array of buckets."),
                new Line("5.2 Multiplicative Hashing", 11, true),
                body("Multiplicative hashing uses a random odd multiplier."))));

        assertThat(markdown).contains("## 5.2 Multiplicative Hashing");
    }

    @Test
    void aBoldLineWithoutASectionNumberIsNotAHeading() throws IOException {
        String markdown = extract(List.of(List.of(
                heading("Hash Tables", 20),
                body("A hash table stores elements in buckets."),
                new Line("load factor", 11, true),
                body("The load factor is the ratio of elements to buckets."))));

        assertThat(markdown).doesNotContain("# load factor");
        assertThat(markdown).contains("load factor");
    }

    @Test
    void aLongSentenceSetLargeIsNotAHeading() throws IOException {
        // Display text is not structure. A pull quote or a theorem statement set in a larger face
        // would otherwise open a section and cut the one it belongs to in half.
        String sentence = "The expected running time of every operation on a skiplist is "
                + "logarithmic in the number of elements it currently contains, taken over the "
                + "coin tosses the structure itself performed";
        String markdown = extract(List.of(List.of(
                heading("Skiplists", 20),
                body("A skiplist is a randomized structure."),
                new Line(sentence, 14, false))));

        assertThat(markdown).doesNotContain("# " + sentence);
    }

    @Test
    void aRunningHeadRepeatedAtThePageEdgeIsDropped() throws IOException {
        // The line that made this rule necessary: page furniture is indexed like any other text,
        // and a running head sitting between the two halves of a paragraph that spans a page break
        // splits a sentence with the chapter's title.
        List<List<Line>> pages = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            pages.add(List.of(
                    body("CHAPTER 4. SKIPLISTS"),
                    body("Body text on page " + page + " discussing the structure at length."),
                    body("42")));
        }
        String markdown = extract(pages);

        assertThat(markdown).doesNotContain("CHAPTER 4. SKIPLISTS");
        assertThat(markdown).doesNotContain("42");
        assertThat(markdown).contains("Body text on page 2");
    }

    @Test
    void aWordBrokenAcrossLinesIsPutBackTogether() throws IOException {
        // "algo- rithm" matches neither "algorithm" nor anything a student would type, and a
        // hyphenated break happens several times a page in a typeset book.
        String markdown = extract(List.of(List.of(
                body("The running time of the algo-"),
                body("rithm is logarithmic in n."))));

        assertThat(markdown).contains("algorithm is logarithmic");
    }

    @Test
    void arealHyphenSurvives() throws IOException {
        String markdown = extract(List.of(List.of(
                body("This is the Dijkstra-"),
                body("Scholten termination algorithm."))));

        assertThat(markdown).contains("Dijkstra- Scholten");
    }

    @Test
    void everyPageIsReportedEvenWhenItHasNoText() throws IOException {
        List<PageText> pages = extractor.extract(pdf(List.of(
                List.of(body("First page of the document.")),
                List.of(),
                List.of(body("Third page of the document.")))));

        assertThat(pages).hasSize(3);
        assertThat(pages.get(1).text()).isEmpty();
        // The page count is what the document row records and what the citation viewer pages
        // through, so a blank page has to keep its place in the numbering.
        assertThat(pages.get(2).pageNumber()).isEqualTo(3);
    }
}
