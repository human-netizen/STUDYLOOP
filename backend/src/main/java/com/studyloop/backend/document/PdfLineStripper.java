package com.studyloop.backend.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Reads a PDF as a list of lines that remember how they were typeset (Phase 13.2).
//
// PDFBox's ordinary `getText` throws that away — it returns a wall of characters, and once the
// font sizes are gone there is no way to tell a chapter heading from the sentence under it. That
// loss is what forced fixed-window chunking: with no structure in the input, a counter is the only
// thing left to put a boundary at.
//
// The mechanism is PDFTextStripper's own writer hooks. The base class already solves the hard part
// — grouping glyphs into lines in reading order — and calls `writeString` for each run of text and
// `writeLineSeparator` at the end of each line. Overriding those collects the lines instead of
// printing them, so nothing here re-implements layout analysis; the output writer is fed nothing
// and thrown away.
class PdfLineStripper extends PDFTextStripper {

    private final List<PdfLine> lines = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();
    private final List<TextPosition> buffered = new ArrayList<>();

    PdfLineStripper() throws IOException {
        // Reading order by position, not by the order the glyphs happen to appear in the content
        // stream. Multi-column layouts are the reason: unsorted, a two-column page interleaves the
        // columns line by line and every sentence in it is nonsense.
        setSortByPosition(true);
    }

    static List<PdfLine> read(PDDocument document) throws IOException {
        PdfLineStripper stripper = new PdfLineStripper();
        // The result is deliberately discarded: the lines are the output, and `writeText` is just
        // the loop that drives the hooks below.
        stripper.writeText(document, new StringWriter());
        return stripper.lines;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        buffer.append(text);
        buffered.addAll(textPositions);
    }

    @Override
    protected void writeWordSeparator() {
        buffer.append(' ');
    }

    @Override
    protected void writeLineSeparator() {
        flush();
    }

    // PDFBox's own paragraph detection fires here. It is not used as a paragraph signal — the gap
    // rule in PdfTextExtractor is — but it must still close the line, or the paragraph's first line
    // would be glued onto the last line of the one before it.
    @Override
    protected void writeParagraphSeparator() {
        flush();
    }

    @Override
    protected void endPage(PDPage page) {
        // A page whose last line has no separator after it, which is the common case.
        flush();
    }

    private void flush() {
        String text = buffer.toString().strip();
        buffer.setLength(0);
        List<TextPosition> positions = List.copyOf(buffered);
        buffered.clear();
        if (text.isEmpty() || positions.isEmpty()) {
            return;
        }
        lines.add(new PdfLine(getCurrentPageNo(), text,
                dominantSize(positions), isBold(positions), positions.get(0).getYDirAdj()));
    }

    // The size most of the line's characters were set in, not the largest one. A body line that
    // happens to contain one oversized symbol — an integral sign, a summation — is a body line,
    // and taking the maximum would promote it to a heading.
    private static float dominantSize(List<TextPosition> positions) {
        Map<Float, Integer> weight = new HashMap<>();
        for (TextPosition position : positions) {
            float size = round(position.getFontSizeInPt());
            weight.merge(size, position.getUnicode() == null ? 1 : position.getUnicode().length(), Integer::sum);
        }
        return weight.entrySet().stream()
                .max(Map.Entry.<Float, Integer>comparingByValue()
                        // Ties to the larger size, so a half-and-half line reads as the heading it
                        // probably is rather than flipping on the order of a hash map.
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(0f);
    }

    // Half-point buckets. PDF font sizes are floats and the same visual size can arrive as 11.9999
    // and 12.0001 on two lines; unrounded, those are two different "sizes" and the histogram that
    // finds the body size fragments into noise.
    private static float round(float size) {
        return Math.round(size * 2f) / 2f;
    }

    // Bold is read off the font's name because that is where a PDF records it. There is no bold
    // flag on a glyph: the file references a different font program ("...-Bold", "...-Black"), and
    // the name is the only part of it that is reliably present.
    private static boolean isBold(List<TextPosition> positions) {
        PDFont font = positions.get(0).getFont();
        if (font == null || font.getName() == null) {
            return false;
        }
        String name = font.getName().toLowerCase(Locale.ROOT);
        return name.contains("bold") || name.contains("black") || name.contains("heavy");
    }
}
