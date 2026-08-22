package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.config.VisionProperties.Thresholds;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Phase 15.1 — scores every page after PDFBox has read it, so only the pages PDFBox got wrong
// cost a vision call.
//
// **The whole argument for this class is cost proportionality.** Sending every page to a VLM is a
// design assertion nobody can price; sending the failures is a measured claim. On the fourteen
// fixture chapters it routes **21 of 306 pages, 6.9%** — twenty-one provider calls for a full
// corpus rebuild — and on material with nothing wrong with it, none. A 300-page scanned book is the
// case the per-document cap in 15.3 exists for.
//
// **Four kinds of failure, five measurements, because a page fails in unrelated ways** and no
// single number sees more than one of them:
//
//   1. **Characters per unit of page area** — near zero means the characters were never in the file.
//   2. **Codepoints outside the expected scripts** — extraction succeeded and returned nonsense,
//      which is what a wrong or missing /ToUnicode CMap does. The page renders fine, which is why
//      nobody notices until they search for a word that is on it.
//   3. **Image coverage, and vector segments drawn** — a page whose substance is a diagram loses
//      that substance to a text extractor no matter how good the extractor is. Two measurements
//      because there are two ways to put a picture on a page, and 15.1 specified only the first:
//      measured against the fixture corpus, image coverage is **exactly zero on all 306 pages**,
//      because a book typeset in LaTeX draws its figures rather than pasting them. The signal meant
//      to catch figure pages could not fire on the corpus whose figure questions have been the
//      weakest column in the golden set since Phase 12.
//   4. **Sorted vs unsorted reading order** — PDFBox sorts by position to fix multi-column pages;
//      where sorting *changes* the answer a lot, the page is one whose reading order is a guess.
//      This is also the reason plain OCR is not the fallback: Tesseract guesses the same way.
//
// **A blank page is not a defect.** Low text with no picture on it is a chapter opener or a spacer,
// and routing it buys a vision call and an empty answer. Every low-text verdict below therefore
// also requires something to have been drawn — which is what tells "photograph of a page" apart
// from "no page".
//
// Cost: two text passes per page on top of the one PdfTextExtractor already runs, plus one
// content-stream walk that decodes no images. On a fourteen-chapter corpus that is seconds, inside
// an asynchronous pipeline nobody is waiting on. The unsorted pass is skipped for a page with
// almost no text, where there is no order to disagree about.
@Component
@RequiredArgsConstructor
public class PageQualityGate {

    private static final Logger log = LoggerFactory.getLogger(PageQualityGate.class);

    // Below this many non-space characters a page has not said enough for the encoding and ordering
    // signals to mean anything: three stray characters can be 100% foreign and 100% misordered.
    //
    // Counted as *non-whitespace*, not as letters, and that distinction was a bug before it was a
    // comment. A page whose /ToUnicode CMap is broken has no letters on it by definition — every
    // codepoint is private-use — so a letter count is zero exactly when the encoding signal has the
    // most to say, and the check meant to protect the signal switched it off instead.
    private static final int MIN_CHARS_TO_JUDGE = 40;
    // Same idea for the ordering signal, which compares sequences of words rather than characters.
    private static final int MIN_WORDS_TO_JUDGE = 25;
    // Ceiling on the same comparison, which is quadratic in the words on the page. A thousand is
    // more than a dense textbook page holds, and a reading order that is wrong is wrong early.
    private static final int MAX_WORDS_COMPARED = 1_000;
    // Page area is quoted per thousand square points, so a US Letter page is ~485 units and the
    // thresholds are small readable numbers rather than four-decimal ones.
    private static final double AREA_UNIT = 1_000.0;

    private final VisionProperties properties;

    // Scores the whole document, one entry per page, in page order.
    //
    // Never throws. A page whose content stream cannot be walked is scored as clean rather than
    // routed: the gate is an optimisation over what PDFBox already produced, and a gate that could
    // fail an upload would be a new way to lose a document that used to ingest.
    public List<PageQuality> score(PDDocument document) {
        Set<Character.UnicodeScript> expected = expectedScripts();
        Thresholds thresholds = properties.thresholds();
        List<PageQuality> qualities = new ArrayList<>(document.getNumberOfPages());

        for (int index = 0; index < document.getNumberOfPages(); index++) {
            try {
                qualities.add(scorePage(document, index, expected, thresholds));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not score page {} for extraction quality: {}", index + 1, e.toString());
                qualities.add(new PageQuality(index + 1, 0, 0, 0, 0, 0, null));
            }
        }
        return qualities;
    }

    private PageQuality scorePage(PDDocument document, int index,
                                  Set<Character.UnicodeScript> expected, Thresholds thresholds)
            throws IOException {
        PDPage page = document.getPage(index);
        String sorted = extract(document, index, true);

        float width = page.getMediaBox().getWidth();
        float height = page.getMediaBox().getHeight();
        double areaUnits = (double) width * height / AREA_UNIT;
        double charsPerArea = areaUnits <= 0 ? 0 : sorted.length() / areaUnits;

        PageGraphics.Drawing drawing = PageGraphics.of(page);
        double foreign = foreignRatio(sorted, expected);
        // Nothing to disagree about on a page with no prose on it, and the unsorted pass is the
        // most expensive of the three measurements.
        double disagreement = countSignificant(sorted) < MIN_CHARS_TO_JUDGE
                ? 0
                : orderDisagreement(sorted, extract(document, index, false));

        return new PageQuality(index + 1, charsPerArea, foreign, drawing.imageCoverage(),
                drawing.vectorSegments(), disagreement,
                verdict(sorted, charsPerArea, foreign, drawing, disagreement, thresholds));
    }

    // The verdict, most severe first. A scanned page is also, technically, a page of unbroken
    // encoding with no reading order and full image coverage; reporting all four would say nothing
    // about what to do with it. The order is what a reader of the fixture report needs: the reason
    // the page is unusable, not the list of ways it is unusable.
    private static PageDefect verdict(String text, double charsPerArea, double foreign,
                                      PageGraphics.Drawing drawing, double disagreement,
                                      Thresholds thresholds) {
        if (charsPerArea < thresholds.minCharsPerThousandPoints()
                && drawing.imageCoverage() >= thresholds.scannedImageCoverage()) {
            return PageDefect.SCANNED;
        }
        boolean enoughText = countSignificant(text) >= MIN_CHARS_TO_JUDGE;
        if (enoughText && foreign >= thresholds.maxForeignRatio()) {
            return PageDefect.BROKEN_ENCODING;
        }
        if (enoughText && disagreement >= thresholds.maxOrderDisagreement()) {
            return PageDefect.UNRELIABLE_ORDER;
        }
        // A figure is a picture either way it was drawn, and the two ways need different
        // arithmetic: a raster figure is measured by the area it covers, a vector one by how many
        // segments it took to draw. Both are gated on the page not being dense prose, because a
        // full page of text with a small diagram on it already answers most of what it can answer.
        boolean sparse = charsPerArea < thresholds.figureCharsPerThousandPoints();
        if (sparse && drawing.imageCoverage() >= thresholds.figureImageCoverage()) {
            return PageDefect.FIGURE;
        }
        if (sparse && drawing.vectorSegments() >= thresholds.figureVectorSegments()) {
            return PageDefect.FIGURE;
        }
        return null;
    }

    // ── signal 2: is this text, or is it what a broken CMap returned? ───────────────────────────

    // Share of letter-ish codepoints belonging to no expected script.
    //
    // Counted over codepoints rather than chars so a surrogate pair is one symbol, and over
    // non-whitespace only — a page of correct text is mostly spaces, and letting them into the
    // denominator would halve every ratio and make the threshold a number about typography.
    //
    // UNKNOWN is where the private use area lands, which is where a mis-generated /ToUnicode CMap
    // usually points, so it counts as foreign without being named.
    private static double foreignRatio(String text, Set<Character.UnicodeScript> expected) {
        int total = 0;
        int foreign = 0;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (Character.isWhitespace(codepoint)) {
                continue;
            }
            total++;
            if (!expected.contains(scriptOf(codepoint))) {
                foreign++;
            }
        }
        return total == 0 ? 0 : (double) foreign / total;
    }

    private static Character.UnicodeScript scriptOf(int codepoint) {
        try {
            return Character.UnicodeScript.of(codepoint);
        } catch (IllegalArgumentException notACodepoint) {
            return Character.UnicodeScript.UNKNOWN;
        }
    }

    // Configured names resolved once per document. An unrecognised name is dropped with a warning
    // rather than failing the ingest: a typo in a script list should cost the signal accuracy, not
    // cost the user their upload.
    private Set<Character.UnicodeScript> expectedScripts() {
        Set<Character.UnicodeScript> scripts = resolve(properties.expectedScripts());
        // An empty set makes every character on every page foreign — the entire upload routed to a
        // vision model because somebody misspelled a configuration value. The defaults are the only
        // safe reading of "every name here is a typo"; COMMON on its own is not, because COMMON is
        // digits and punctuation and would still call every letter in the corpus foreign.
        return scripts.isEmpty() ? resolve(VisionProperties.DEFAULT_SCRIPTS) : scripts;
    }

    private static Set<Character.UnicodeScript> resolve(List<String> names) {
        Set<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
        for (String name : names) {
            try {
                scripts.add(Character.UnicodeScript.forName(name.strip()));
            } catch (IllegalArgumentException unknown) {
                log.warn("Ignoring unknown Unicode script '{}' in studyloop.vision.expected-scripts", name);
            }
        }
        return scripts;
    }

    // ── signal 4: does sorting the glyphs change what the page says? ────────────────────────────

    // How much of the page the two readings cannot agree to read in the same order: one minus the
    // length of their longest common subsequence, over the longer reading.
    //
    // **Not a position-by-position comparison, and that distinction is the difference between a
    // usable signal and an unusable one.** The first version of this compared word i against word i
    // and it flagged 79 of the 306 pages of a single-column textbook — 26% of a corpus whose text
    // Phase 13 measured at Recall@6 0.939, so those pages are demonstrably fine. The cause is that
    // a positional comparison measures *alignment*, not order: one superscript or figure label that
    // the two passes emit at different moments shifts every word after it by one, and the rest of a
    // perfectly ordered page scores as total disagreement.
    //
    // A longest common subsequence is immune to that, because a shift costs only the words that
    // actually moved. It still sees the thing worth seeing: a two-column page read down the columns
    // one way and across the gutter the other has no long common subsequence — keeping one column
    // in order means abandoning the other — so it lands near 0.5 while a shifted single-column page
    // lands near 0.
    //
    // O(n·m) in the words on one page, on two rolling rows rather than a matrix, and bounded below
    // so a pathological page cannot make ingestion quadratic in something unbounded.
    private static double orderDisagreement(String sorted, String unsorted) {
        String[] a = words(sorted);
        String[] b = words(unsorted);
        if (a.length < MIN_WORDS_TO_JUDGE) {
            return 0;
        }
        int longest = Math.max(a.length, b.length);
        return 1.0 - (double) commonSubsequence(a, b) / longest;
    }

    // Classic two-row LCS. The rows hold word counts, not characters, so even a dense page is a few
    // hundred entries wide.
    private static int commonSubsequence(String[] a, String[] b) {
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                current[j] = a[i - 1].equals(b[j - 1])
                        ? previous[j - 1] + 1
                        : Math.max(previous[j], current[j - 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }

    // Capped, because the comparison above is quadratic and a page's word count is not something
    // this code controls. A thousand words is more than a dense page of a textbook holds, and a
    // reading order that is wrong is wrong within the first thousand.
    private static String[] words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isBlank())
                .limit(MAX_WORDS_COMPARED)
                .toArray(String[]::new);
    }

    private static int countSignificant(String text) {
        return (int) text.codePoints().filter(codepoint -> !Character.isWhitespace(codepoint)).count();
    }

    // One page's plain text, read the way the pipeline reads it (sorted) or the way the file
    // happens to store it (unsorted). Not the Markdown PdfTextExtractor produces: that has already
    // had furniture dropped and headings marked, and comparing it against a raw pass would measure
    // those edits rather than the page.
    private static String extract(PDDocument document, int index, boolean sortByPosition)
            throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(sortByPosition);
        stripper.setStartPage(index + 1);
        stripper.setEndPage(index + 1);
        return stripper.getText(document).strip();
    }

    // Convenience for callers that only want the routing decision, keeping the set semantics in
    // one place: PdfExtractionRouter routes by page number, and so does the fixture report.
    public static Set<Integer> pagesNeedingVision(List<PageQuality> qualities) {
        Set<Integer> pages = new HashSet<>();
        for (PageQuality quality : qualities) {
            if (quality.needsVision()) {
                pages.add(quality.pageNumber());
            }
        }
        return pages;
    }
}
