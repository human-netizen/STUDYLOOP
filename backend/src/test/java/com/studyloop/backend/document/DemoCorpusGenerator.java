package com.studyloop.backend.document;

import com.studyloop.backend.document.TestPdfs.Kind;
import com.studyloop.backend.retrieval.eval.BanglaGoldenSet;
import com.studyloop.backend.retrieval.eval.BanglaGoldenSet.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 23.3's demo corpus, written to disk rather than described (added 2026-09-11).
//
// **The gap this closes.** `src/test/resources/fixtures/` holds fourteen text-layer PDFs — one
// textbook, all of it clean prose — and nothing else. So the three phases the second arc is
// *headlined* on had nothing to demonstrate against: Phase 15's vision router never routes a page,
// Phase 16's PPTX/DOCX readers never see a deck or a document, and Phase 19's Bangla path never sees
// Bangla. A demo and a video filmed on that corpus can only show the features that were already
// working in Phase 6.
//
// **Why generate rather than commit binaries.** Every builder used here is already trusted by the
// suite: `TestPdfs` emits real PDFs whose defects are reproduced mechanically rather than imitated —
// `BROKEN_ENCODING` writes genuine Helvetica text behind a deliberately wrong `/ToUnicode` CMap,
// which *is* the mechanism behind copying from a PDF and getting gibberish — and `TestOfficeFiles`
// writes real OOXML through POI, speaker notes included. Committing binaries would add megabytes to
// a repository that would then have no way to say how they were made.
//
// **Run it:**
//     ./mvnw -o test -Dtest=DemoCorpusGenerator -Ddemo.corpus=true
// writing to `demo/corpus/` under the repository root (override with `-Ddemo.corpus.dir=...`).
// Gated the same way the eval harnesses are, for the same reason: it writes outside `target/` and a
// normal `clean verify` should not litter a working tree.
//
// **One honest limitation, stated here rather than discovered during filming.** Item 7 is a rendered
// image of note-shaped text, not a photograph of handwriting. It gives the vision path a real image
// to read, which is what the pipeline needs, but it does not test what a phone camera does to
// contrast, focus and perspective. For the filmed demo, photograph a real page and use that —
// currentTodo.md's handwriting item exists precisely because only a person can produce one.
@EnabledIfSystemProperty(named = "demo.corpus", matches = "true")
class DemoCorpusGenerator {

    private static final String PROSE =
            "A skiplist is a sequence of singly linked lists, each a subset of the one below. "
            + "Searching walks down from the sparsest list, so the expected number of steps is "
            + "logarithmic in the number of elements rather than linear.";

    @Test
    void writeTheDemoCorpus() throws IOException {
        Path dir = Paths.get(System.getProperty("demo.corpus.dir", "../demo/corpus"));
        Files.createDirectories(dir);

        // 1. A scanned chapter: a page-filling image and no text operators at all. This is the one
        //    that makes Phase 15 visible — PDFBox extracts nothing, the quality gate condemns every
        //    page, and the vision router is the only reason the document answers anything.
        write(dir, "01-scanned-chapter.pdf",
                TestPdfs.of(Kind.SCANNED, Kind.SCANNED, Kind.SCANNED));

        // 2. Broken encoding: extraction *succeeds* and returns private-use codepoints. The
        //    interesting case, because nothing downstream can tell it failed — the document looks
        //    ingested and answers gibberish, which is the failure a coverage number cannot see.
        write(dir, "02-broken-encoding.pdf",
                TestPdfs.of(Kind.BROKEN_ENCODING, Kind.BROKEN_ENCODING));

        // 3. Figures. VECTOR_FIGURE is the one worth having: a diagram *drawn* as hundreds of line
        //    segments with no image XObject anywhere, which is what LaTeX actually produces and what
        //    an image-coverage signal is blind to. DENSE_FIGURE is a clean page of prose *and* a
        //    diagram — Phase 15 correctly declines it and Phase 17 takes it.
        write(dir, "03-figures-and-diagrams.pdf",
                TestPdfs.of(Kind.FIGURE, Kind.VECTOR_FIGURE, Kind.DENSE_FIGURE));

        // 4. A lecture deck **with speaker notes**, which is the whole point of 16.1: the notes are
        //    where a lecturer says the thing the slide only gestures at, and a PDF export of the same
        //    deck loses them entirely.
        write(dir, "04-lecture-deck.pptx", TestOfficeFiles.deck()
                .slide("Hash Tables",
                        List.of("Chaining stores a list per bucket",
                                "Linear probing stores entries in the table itself",
                                "Both degrade as the table fills"),
                        "Say out loud that the load factor is the thing that matters, not the hash "
                        + "function — students always blame the hash function. A chained table at "
                        + "load factor one still has expected constant lookups; a probing table at "
                        + "0.95 does not.")
                .slide("Resizing",
                        List.of("Double when occupancy passes one half",
                                "Halve when it falls below one eighth",
                                "The gap between the two thresholds is what makes it amortized"),
                        "The gap is the exam question. If you grow and shrink at the same threshold, "
                        + "an alternating add/remove sequence rebuilds the table every operation and "
                        + "the amortized bound collapses.")
                .bytes());

        // 5. A Word handout: headings, lists and a table. 16.2's point is that a .docx *declares* its
        //    structure instead of leaving it to be inferred from font sizes, so the chunker has the
        //    author's own section boundaries rather than a guess at them.
        write(dir, "05-handout.docx", TestOfficeFiles.doc()
                .heading("Heading1", "Sorting Lower Bounds")
                .paragraph("Any comparison-based sorting algorithm needs Omega(n log n) comparisons "
                           + "in the worst case, because its comparison tree must have at least n! "
                           + "leaves and a binary tree of that size has depth at least log2(n!).")
                .heading("Heading2", "Beating the bound")
                .listItem("Counting sort: O(n + k) for integers drawn from 0 to k", 0)
                .listItem("Radix sort: O(w n) for w-bit integers, w passes of counting sort", 0)
                .paragraph("Neither is a counterexample: both read the keys rather than comparing "
                           + "them, so the lower bound does not apply to either.")
                .table(List.of(
                        List.of("Algorithm", "Worst case", "In place"),
                        List.of("Merge sort", "n log n", "no"),
                        List.of("Heap sort", "n log n", "yes"),
                        List.of("Quicksort", "n squared", "yes")))
                .bytes());

        // 6. Bangla material, as a .docx. **A .docx rather than a PDF on purpose**: OOXML stores
        //    text, so this needs no Bengali font, while writing Bangla into a PDF with PDFBox needs a
        //    font file with Bengali glyphs that this repository does not carry and should not start
        //    carrying for a demo. The prose is `BanglaGoldenSet`'s — already the measured Bangla
        //    corpus, so the demo and the eval are reading the same material.
        //
        //    Note what this does *not* cover: 19.x's headline defect was a broken-CMap Bangla *PDF*,
        //    and item 2 reproduces the CMap mechanism in English. A genuine Bangla PDF with a broken
        //    CMap is the one fixture that still has to come from the real world.
        TestOfficeFiles.Doc bangla = TestOfficeFiles.doc()
                .heading("Heading1", "তথ্য কাঠামো");
        for (Section section : BanglaGoldenSet.SECTIONS) {
            bangla.heading("Heading2", section.heading()).paragraph(section.body());
        }
        write(dir, "06-bangla-notes.docx", bangla.bytes());

        // 7. A note-shaped image for the handwriting reader. See the class comment: this is a real
        //    image and not a real photograph.
        write(dir, "07-notebook-page.png", notebookPage());

        // The generator's own check. A zero-byte or near-empty file here would be a corpus that looks
        // present in a file listing and demonstrates nothing, which is the failure mode this whole
        // class exists to fix — so assert on the bytes rather than on the filenames.
        assertThat(Files.list(dir)).isNotNull();
        for (String name : List.of("01-scanned-chapter.pdf", "02-broken-encoding.pdf",
                "03-figures-and-diagrams.pdf", "04-lecture-deck.pptx", "05-handout.docx",
                "06-bangla-notes.docx", "07-notebook-page.png")) {
            assertThat(Files.size(dir.resolve(name)))
                    .as("%s should be a real file, not an empty placeholder", name)
                    .isGreaterThan(1024);
        }
    }

    private static void write(Path dir, String name, byte[] bytes) throws IOException {
        Files.write(dir.resolve(name), bytes);
        System.out.printf("wrote %s (%,d bytes)%n", dir.resolve(name).normalize(), bytes.length);
    }

    // Text on a page, rendered in an italic serif at a size and line spacing that reads like a
    // written note rather than a printout. Antialiasing on, because the vision model is reading
    // glyph shapes and a jagged edge is a different input from a smooth one.
    private static byte[] notebookPage() throws IOException {
        BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Slightly off-white, because pure white is the one thing a photographed page never is.
        g.setColor(new Color(0xFD, 0xFC, 0xF5));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        // Ruled lines, faint, like a notebook.
        g.setColor(new Color(0xD8, 0xE4, 0xF0));
        for (int y = 160; y < image.getHeight() - 80; y += 64) {
            g.drawLine(90, y, image.getWidth() - 80, y);
        }
        g.setColor(new Color(0xF0, 0xC0, 0xC0));
        g.drawLine(150, 60, 150, image.getHeight() - 60);

        g.setColor(new Color(0x1A, 0x23, 0x5A));
        g.setFont(new Font(Font.SERIF, Font.BOLD | Font.ITALIC, 46));
        g.drawString("Lecture 9 - Red-Black Trees", 170, 130);

        g.setFont(new Font(Font.SERIF, Font.ITALIC, 34));
        List<String> lines = List.of(
                "Every node is red or black. The root is black.",
                "No red node has a red child (no two reds in a row).",
                "Every root-to-leaf path has the same number of",
                "black nodes - that count is the black-height.",
                "",
                "So the shortest path is all black, length bh,",
                "and the longest alternates red/black, length 2bh.",
                "A tree with n nodes therefore has height at most",
                "2 log(n+1) - the bound is only a factor of two",
                "worse than a perfectly balanced tree.",
                "",
                "Insert: colour the new node red, then fix upwards.",
                "Three cases - uncle red (recolour), uncle black",
                "with a zig-zag (rotate then recolour), uncle black",
                "straight (rotate once). Only the first recurses.");
        int y = 215;
        for (String line : lines) {
            g.drawString(line, 170, y);
            y += 64;
        }
        g.dispose();

        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
