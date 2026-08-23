package com.studyloop.backend.document;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// PDFs that are broken in one specific way each, built at test time (Phase 15).
//
// Built rather than committed, and that is the point of the class. The gate's whole claim is that
// it can tell four *mechanisms* apart, and a mechanism is only pinned down by a file that has
// exactly it and nothing else. A committed scan would also be a particular DPI, a particular
// compression and a particular language, and a test over it could pass for any of those reasons.
// Here the difference between the clean page and the broken one is a single deliberate edit.
//
// It also keeps the repository honest about size: the eval fixtures are fourteen real chapters
// because retrieval has to be measured on real prose, and none of these has to be.
public final class TestPdfs {

    // Roughly 80 characters, which makes the page arithmetic legible: a US Letter page is ~485
    // units of a thousand square points, so twenty-five lines of this is a little over four
    // characters per unit — an ordinary page of a book.
    private static final String SENTENCE =
            "A skiplist is a sequence of singly linked lists, each a subset of the one below.";

    // Enough distinct prose to fill two columns without a line resembling its neighbour.
    private static final String COLUMN_PROSE = String.join(" ",
            "Comparison based sorting requires at least n log n comparisons in the worst case,",
            "a bound that follows from counting the leaves of a decision tree rather than from",
            "any property of a particular algorithm. Quicksort attains it in expectation by",
            "choosing a pivot uniformly at random, while merge sort attains it deterministically",
            "at the cost of linear auxiliary space. Radix sort escapes the bound entirely because",
            "it never compares two keys; it reads their digits instead, which is only available",
            "when the keys are integers of bounded width. Heapsort matches merge sort",
            "asymptotically and sorts in place, but its access pattern defeats every cache the",
            "machine has, so it loses to quicksort by a constant factor nobody sees in the proof.");

    private TestPdfs() {
    }

    // What a page is wrong about, or PROSE for a page that is not wrong about anything.
    public enum Kind {
        // Ordinary single-column text. The control: every signal must read clean on it, or the
        // router would send a working corpus to a vision model.
        PROSE,
        // No text and no image. Not a defect — a chapter opener or a spacer — and the case that
        // stops "almost no text" on its own from being the scanned rule.
        BLANK,
        // A page-filling image and no text operators at all: a photograph of writing.
        SCANNED,
        // Real Helvetica text with a deliberately wrong /ToUnicode CMap, so extraction succeeds and
        // returns private-use codepoints. This is the actual mechanism behind copying from a PDF
        // and getting gibberish, reproduced rather than imitated.
        BROKEN_ENCODING,
        // Two columns emitted one after the other, so the content-stream order and the
        // position-sorted order disagree about almost every word.
        TWO_COLUMN,
        // A figure over a quarter of the page with a caption's worth of text around it. Extraction
        // works perfectly here and still misses what the page is about.
        FIGURE,
        // The same page with the figure *drawn* instead of pasted — hundreds of line segments, no
        // image XObject anywhere. This is what a LaTeX book actually produces, and it is the case
        // the plan's image-coverage signal is blind to: the fourteen fixture chapters contain zero
        // image XObjects across all 306 pages and a diagram on dozens of them.
        VECTOR_FIGURE,
        // A full page of prose *and* a diagram — the page the two phases disagree about.
        //
        // Phase 15's gate declines it, correctly: the text extracted perfectly and its job was the
        // text. Phase 17 takes it, because a page whose diagram nobody captioned is unfindable no
        // matter how good the prose around it is. Every existing signal reads this page as clean,
        // which is exactly why it needs its own fixture.
        DENSE_FIGURE
    }

    public static byte[] of(Kind... kinds) {
        try (PDDocument document = new PDDocument()) {
            for (Kind kind : kinds) {
                addPage(document, kind);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addPage(PDDocument document, Kind kind) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        switch (kind) {
            case PROSE -> writeLines(document, page, 25, false);
            case BLANK -> { }
            case SCANNED -> drawImage(document, page, 0, 0,
                    page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            case BROKEN_ENCODING -> writeLines(document, page, 25, true);
            case TWO_COLUMN -> writeColumns(document, page);
            case FIGURE -> {
                // Five lines is ~400 characters, which is 0.8 per unit of area: above the "no text
                // at all" floor and below the "this page is prose" ceiling, which is exactly the
                // band a figure page sits in.
                writeLines(document, page, 5, false);
                drawImage(document, page, 100, 200, 400, 350);
            }
            case VECTOR_FIGURE -> {
                writeLines(document, page, 5, false);
                drawTree(document, page);
            }
            case DENSE_FIGURE -> {
                // Twenty-five lines is an ordinary page of a book, which puts chars-per-area above
                // the figure gate's sparse ceiling. The tree underneath it is the same tree.
                writeLines(document, page, 25, false);
                drawTree(document, page);
            }
        }
    }

    // A binary tree, drawn the way a typeset textbook draws one: nodes as circles built from curve
    // segments, edges as lines. Nothing here is an image, which is the entire point — this page has
    // an image coverage of exactly zero and is plainly a figure.
    private static void drawTree(PDDocument document, PDPage page) throws IOException {
        try (PDPageContentStream content = new PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true)) {
            content.setLineWidth(0.6f);
            // Five levels: 31 nodes at five segments each (a circle is four Bezier arcs and a
            // move) plus 30 two-segment edges, so 215 segments. A four-level tree comes to 103 and
            // sits under the threshold, which is the right answer — a diagram that small is a
            // decoration next to a paragraph, not the substance of the page.
            for (int depth = 0; depth < 5; depth++) {
                int nodes = 1 << depth;
                float y = 620 - depth * 90;
                float spacing = 480f / (nodes + 1);
                for (int i = 0; i < nodes; i++) {
                    float x = 60 + spacing * (i + 1);
                    circle(content, x, y, 9);
                    if (depth > 0) {
                        float parentSpacing = 480f / ((nodes / 2) + 1);
                        content.moveTo(x, y + 9);
                        content.lineTo(60 + parentSpacing * ((i / 2) + 1), y + 90 - 9);
                        content.stroke();
                    }
                }
            }
        }
    }

    // Four Bezier arcs, the standard way to approximate a circle in PostScript-descended graphics.
    private static void circle(PDPageContentStream content, float cx, float cy, float r)
            throws IOException {
        float k = r * 0.5523f;
        content.moveTo(cx - r, cy);
        content.curveTo(cx - r, cy + k, cx - k, cy + r, cx, cy + r);
        content.curveTo(cx + k, cy + r, cx + r, cy + k, cx + r, cy);
        content.curveTo(cx + r, cy - k, cx + k, cy - r, cx, cy - r);
        content.curveTo(cx - k, cy - r, cx - r, cy - k, cx - r, cy);
        content.stroke();
    }

    private static void writeLines(PDDocument document, PDPage page, int count,
                                   boolean breakEncoding) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        if (breakEncoding) {
            corruptToUnicode(document, font);
        }
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            float y = 740;
            for (int i = 0; i < count; i++) {
                content.beginText();
                content.setFont(font, 10);
                content.newLineAtOffset(72, y);
                content.showText(SENTENCE);
                content.endText();
                y -= 14;
            }
        }
    }

    // Left column in full, then right column in full — the order a typesetter emits them and the
    // order PDFBox reads them without sorting. Sorted by position the same page reads across the
    // gutter, one line of the left column then one line of the right, so the two passes share
    // almost no positions. Neither pass is "wrong"; the disagreement is the signal.
    //
    // The text is a run of *different* fragments rather than numbered copies of one line, and that
    // is load-bearing rather than decorative. The first version of this fixture wrote "column left
    // line 7 of the page under test" on every line, which shares seven of its nine words with
    // whatever it was compared against — the two readings came out 0.16 apart and the page scored
    // clean. A fixture whose lines resemble each other measures how repetitive the fixture is, not
    // how badly the page is ordered.
    private static void writeColumns(PDDocument document, PDPage page) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        List<String> lines = wrap(COLUMN_PROSE, 7);
        int perColumn = lines.size() / 2;
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (int column = 0; column < 2; column++) {
                float y = 740;
                for (int i = 0; i < perColumn; i++) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.newLineAtOffset(column == 0 ? 60 : 320, y);
                    content.showText(lines.get(column * perColumn + i));
                    content.endText();
                    y -= 14;
                }
            }
        }
    }

    // Greedy wrap to a word count, which is all a fixture needs: the point is that consecutive
    // lines hold different words, not that the right margin is straight.
    private static List<String> wrap(String prose, int wordsPerLine) {
        String[] words = prose.split("\\s+");
        List<String> lines = new ArrayList<>();
        for (int start = 0; start + wordsPerLine <= words.length; start += wordsPerLine) {
            lines.add(String.join(" ", Arrays.copyOfRange(words, start, start + wordsPerLine)));
        }
        return lines;
    }

    private static void drawImage(PDDocument document, PDPage page, float x, float y,
                                  float width, float height) throws IOException {
        BufferedImage bitmap = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bitmap.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 800);
        graphics.setColor(Color.DARK_GRAY);
        // Bars, so the image is not a flat colour a future encoder could optimise into nothing.
        // Nothing here decodes the pixels — the gate measures the placement — but a degenerate
        // image is a bad fixture to leave lying around.
        for (int row = 0; row < 20; row++) {
            graphics.fillRect(40, 40 + row * 36, 520, 18);
        }
        graphics.dispose();

        PDImageXObject image = LosslessFactory.createFromImage(document, bitmap);
        try (PDPageContentStream content = new PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true)) {
            content.drawImage(image, x, y, width, height);
        }
    }

    // Replaces the font's character map with one sending every printable ASCII code into the
    // Private Use Area.
    //
    // This is what a broken PDF actually looks like. /ToUnicode is the optional table saying what
    // each glyph *means*; drawing never consults it, so the page renders as perfect Helvetica and
    // extracts as U+E041, U+E020, U+E073... A tool that subsets a font and forgets to rebuild this
    // table produces exactly this, which is why "the PDF looks fine, the search finds nothing" is
    // such a common report.
    private static void corruptToUnicode(PDDocument document, PDType1Font font) throws IOException {
        String cmap = """
                /CIDInit /ProcSet findresource begin
                12 dict begin
                begincmap
                /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                /CMapName /Broken-UCS2 def
                /CMapType 2 def
                1 begincodespacerange
                <00> <FF>
                endcodespacerange
                1 beginbfrange
                <20> <7e> <e020>
                endbfrange
                endcmap
                CMapName currentdict /CMap defineresource pop
                end
                end
                """;
        PDStream stream = new PDStream(document,
                new ByteArrayInputStream(cmap.getBytes(StandardCharsets.US_ASCII)));
        // Set on the COS dictionary rather than through the PDFont API, because there is no API
        // for it: PDFBox reads /ToUnicode when a font is constructed from a dictionary, which is
        // what happens when the saved file is loaded back.
        font.getCOSObject().setItem(COSName.TO_UNICODE, stream);
    }
}
