package com.studyloop.backend.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

// Turns a PDF into **Markdown**, one page at a time (Phase 13.2).
//
// The page-at-a-time part is unchanged and still exists for citations: a chunk has to be traceable
// to the page it came from, or clicking [3] cannot open anything. What changed is what a page is.
// It used to be a wall of characters; it is now Markdown with `#` headings, which is the
// intermediate representation the rest of the pipeline is built on.
//
// **Why an IR rather than a private object.** Every later input format converts into this same
// Markdown — a vision model's reading of a scanned page (15.2), a slide deck's text and speaker
// notes (16.1), a Word document (16.2), a photographed note (16.3). If the chunker consumed some
// bespoke `ExtractedDocument` type, each of those would be a second pipeline; because it consumes
// Markdown, each of them is a converter and the chunker never learns they exist.
//
// **How a heading is recognised.** A PDF has no heading marks in it — headings are a visual
// convention, not a structure the file records. So the evidence is typographic: a line set larger
// than the body text, or a bold numbered line ("4.2 Skiplists"). The body size is whichever size
// most of the document's characters were set in, which is a histogram rather than a constant and
// therefore survives a document that sets its body text at 9pt and one that sets it at 12.
@Component
public class PdfTextExtractor {

    // How much larger than the body text a line must be set to read as a heading. Below ~10% is
    // within the range of a font falling back to a slightly different metric, above ~20% misses the
    // subsection headings in books that only step up a point at a time.
    private static final float HEADING_SIZE_RATIO = 1.12f;
    // Distinct heading sizes to honour. Beyond four the document is telling us about typography,
    // not structure, and deeper levels do not change where a chunk boundary goes.
    private static final int MAX_HEADING_LEVELS = 4;
    // Headings are short. Anything longer is a sentence set in a display face — a pull quote, a
    // theorem statement — and treating it as a section title would cut the section it belongs to.
    private static final int MAX_HEADING_CHARS = 120;
    private static final int MAX_HEADING_WORDS = 14;
    // A line repeating on more than this many pages is furniture: a running head, a footer, a
    // copyright line. It is dropped rather than demoted, because leaving it in puts the chapter
    // title in the middle of every paragraph that spans a page break.
    private static final int RUNNING_HEAD_PAGES = 3;
    // Furniture is short. A repeated line longer than this is a boilerplate paragraph — a licence
    // notice, a figure caption reused across a series — and dropping it would lose real text.
    private static final int MAX_FURNITURE_CHARS = 80;
    // A new paragraph is a vertical gap noticeably bigger than the document's own line spacing.
    private static final float PARAGRAPH_GAP_RATIO = 1.35f;

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s+\\S");
    private static final Pattern PAGE_NUMBER_ONLY = Pattern.compile("^[ivxlcdm\\d]{1,6}$");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    public List<PageText> extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return extract(document);
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "Could not read the PDF. It may be corrupt or password-protected.", e);
        }
    }

    // The same extraction against an already-open document, so Phase 15's router can score pages
    // and render the failures off the one parse rather than loading the file three times. The
    // caller owns the document and closes it.
    public List<PageText> extract(PDDocument document) {
        try {
            List<PdfLine> lines = keepContent(PdfLineStripper.read(document));
            return toMarkdown(lines, document.getNumberOfPages());
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "Could not read the PDF. It may be corrupt or password-protected.", e);
        }
    }

    // ── furniture removal ───────────────────────────────────────────────────────────────────────

    // Drops running heads, footers and bare page numbers. This is not tidiness: page furniture is
    // indexed like everything else, so "12" in a query matches the corner of every page, and a
    // running head sitting between two halves of a paragraph that spans a page break splits a
    // sentence with the chapter's title.
    //
    // Two rules, because one is not enough. A line repeating on many pages is furniture wherever it
    // sits. A line repeating on even *two* pages is furniture if it is the top or bottom line of
    // both — which is the case the first rule misses, since a running head that names the current
    // section changes every few pages ("§4.1 The Basic Structure", then "§4.2 Skiplist SSet") and
    // never reaches a high repeat count.
    private static List<PdfLine> keepContent(List<PdfLine> lines) {
        Map<String, Set<Integer>> pagesByText = new HashMap<>();
        for (PdfLine line : lines) {
            if (line.text().length() <= MAX_FURNITURE_CHARS) {
                pagesByText.computeIfAbsent(line.text(), key -> new HashSet<>()).add(line.page());
            }
        }
        Set<Integer> edges = pageEdges(lines);

        List<PdfLine> kept = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            PdfLine line = lines.get(i);
            if (PAGE_NUMBER_ONLY.matcher(line.text().toLowerCase()).matches()) {
                continue;
            }
            int repeats = pagesByText.getOrDefault(line.text(), Set.of()).size();
            if (repeats > RUNNING_HEAD_PAGES || (edges.contains(i) && repeats > 1)) {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }

    // Indices of the first and last line of every page — the only two places furniture is printed.
    private static Set<Integer> pageEdges(List<PdfLine> lines) {
        Set<Integer> edges = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            boolean firstOnPage = i == 0 || lines.get(i - 1).page() != lines.get(i).page();
            boolean lastOnPage = i == lines.size() - 1 || lines.get(i + 1).page() != lines.get(i).page();
            if (firstOnPage || lastOnPage) {
                edges.add(i);
            }
        }
        return edges;
    }

    // ── markdown assembly ───────────────────────────────────────────────────────────────────────

    private static List<PageText> toMarkdown(List<PdfLine> lines, int pageCount) {
        Map<Float, Integer> levels = headingLevels(lines);
        float medianGap = medianLineGap(lines);

        // Every page gets an entry even if it holds no text, because the page count is what the
        // document row records and what the citation viewer pages through.
        List<StringBuilder> pages = new ArrayList<>(pageCount);
        for (int page = 0; page < pageCount; page++) {
            pages.add(new StringBuilder());
        }

        PdfLine previous = null;
        for (PdfLine line : lines) {
            int index = line.page() - 1;
            if (index < 0 || index >= pages.size()) {
                continue;
            }
            StringBuilder page = pages.get(index);
            Integer level = headingLevelOf(line, levels);

            if (level != null) {
                appendBlockBreak(page);
                page.append("#".repeat(level)).append(' ').append(line.text()).append('\n');
                previous = null;
                continue;
            }
            if (previous == null || startsParagraph(previous, line, medianGap)) {
                appendBlockBreak(page);
                page.append(line.text());
            } else {
                appendContinuation(page, line.text());
            }
            previous = line;
        }

        List<PageText> texts = new ArrayList<>(pageCount);
        for (int page = 0; page < pageCount; page++) {
            texts.add(new PageText(page + 1, pages.get(page).toString().strip()));
        }
        return texts;
    }

    private static void appendBlockBreak(StringBuilder page) {
        if (page.length() > 0) {
            while (page.length() > 0 && page.charAt(page.length() - 1) == '\n') {
                page.setLength(page.length() - 1);
            }
            page.append("\n\n");
        }
    }

    // Joins a wrapped line onto the one above it, undoing the hyphen the typesetter added to break
    // a word across lines. Without this the index holds "algo- rithm", which matches neither
    // "algorithm" nor anything else a student would type. Only a lowercase continuation is joined:
    // "Dijkstra-" followed by "Scholten" is a real hyphen in a name.
    private static void appendContinuation(StringBuilder page, String text) {
        int end = page.length();
        if (end > 1 && page.charAt(end - 1) == '-' && !text.isEmpty()
                && Character.isLowerCase(text.charAt(0)) && Character.isLetter(page.charAt(end - 2))) {
            page.setLength(end - 1);
            page.append(text);
            return;
        }
        page.append(' ').append(text);
    }

    private static boolean startsParagraph(PdfLine previous, PdfLine line, float medianGap) {
        if (previous.page() != line.page()) {
            // A paragraph that runs over a page break is still one paragraph; the two halves are
            // glued back together and the chunk keeps both pages in its span.
            return false;
        }
        return medianGap > 0 && (line.y() - previous.y()) > medianGap * PARAGRAPH_GAP_RATIO;
    }

    // The typical distance between consecutive baselines — the document's leading. Taken as a
    // median rather than a mean because a page break, a figure or a heading produces a handful of
    // enormous gaps, and a mean would let those decide what a paragraph is.
    private static float medianLineGap(List<PdfLine> lines) {
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            PdfLine previous = lines.get(i - 1);
            PdfLine line = lines.get(i);
            float gap = line.y() - previous.y();
            if (previous.page() == line.page() && gap > 0) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 0f;
        }
        gaps.sort(Float::compare);
        return gaps.get(gaps.size() / 2);
    }

    // ── heading detection ───────────────────────────────────────────────────────────────────────

    // Maps each font size that reads as a heading to its Markdown level. Sizes are ranked, so the
    // levels come out of the document's own typography rather than out of a table of point sizes:
    // the largest heading size in this document is `#`, the next is `##`, and a document that only
    // uses one heading size only ever emits `#`.
    private static Map<Float, Integer> headingLevels(List<PdfLine> lines) {
        float body = bodySize(lines);
        if (body <= 0) {
            return Map.of();
        }
        TreeSet<Float> larger = new TreeSet<>((a, b) -> Float.compare(b, a));
        for (PdfLine line : lines) {
            if (line.size() >= body * HEADING_SIZE_RATIO && couldBeHeading(line)) {
                larger.add(line.size());
            }
        }
        Map<Float, Integer> levels = new HashMap<>();
        int level = 1;
        for (Float size : larger) {
            levels.put(size, Math.min(level++, MAX_HEADING_LEVELS));
        }
        return levels;
    }

    // The size most of the document's characters are set in. Not the most common *line* size: a
    // book of short headings and long paragraphs has more paragraph characters than heading ones,
    // which is the fact that identifies body text, and counting lines would blur it.
    private static float bodySize(List<PdfLine> lines) {
        Map<Float, Integer> characters = new HashMap<>();
        for (PdfLine line : lines) {
            characters.merge(line.size(), line.text().length(), Integer::sum);
        }
        return characters.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0f);
    }

    private static Integer headingLevelOf(PdfLine line, Map<Float, Integer> levels) {
        if (!couldBeHeading(line)) {
            return null;
        }
        Integer bySize = levels.get(line.size());
        if (bySize != null) {
            return bySize;
        }
        // The fallback for books that mark a subsection bold without enlarging it. Bold alone is
        // far too common to be evidence — half the terms in a textbook are bold — so it only counts
        // alongside a section number, and the number decides the depth: "4" is `#`, "4.2" is `##`.
        if (line.bold() && NUMBERED_HEADING.matcher(line.text()).find()) {
            long dots = line.text().split("\\s+")[0].chars().filter(c -> c == '.').count();
            return Math.min((int) dots + 1, MAX_HEADING_LEVELS);
        }
        return null;
    }

    private static boolean couldBeHeading(PdfLine line) {
        String text = line.text();
        if (text.length() < 2 || text.length() > MAX_HEADING_CHARS) {
            return false;
        }
        if (text.split("\\s+").length > MAX_HEADING_WORDS) {
            return false;
        }
        if (text.endsWith(",") || text.endsWith(";")) {
            // A clause continued on the next line, whatever size it was set in.
            return false;
        }
        // Displayed equations are set large and are mostly operators. Requiring that half the line
        // be ordinary text keeps them out without needing to know any mathematics.
        return LETTER.matcher(text).find() && letterRatio(text) >= 0.5;
    }

    private static double letterRatio(String text) {
        long ordinary = text.chars()
                .filter(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c))
                .count();
        return (double) ordinary / text.length();
    }
}
