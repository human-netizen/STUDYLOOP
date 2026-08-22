package com.studyloop.backend.document;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Reads a PowerPoint deck into Markdown, one page per slide (Phase 16.1).
//
// **Why the deck and not a PDF export of it.** A slide deck exported to PDF is the pathological
// input for everything Phase 13 built: no heading hierarchy survives the export, so tier 1 finds
// nothing and the whole document falls through to the semantic splitter; the running order becomes
// positioned text boxes; and the speaker notes — which are usually the only prose in the file — are
// simply gone. Read as OOXML instead, all of that is structure the author already wrote down. A
// slide has a title element, a body element and a notes element, and they are labelled as such.
//
// **A slide is a tier-1 structural unit and needs no new chunker.** The title becomes an `#`
// heading, so `StructuralSplitter` cuts on slide boundaries for free and every chunk's section path
// names the slide it came from. The slide number becomes the page number, so a citation reads
// "slide 12" to a student who counts slides — the same number they see in the corner.
//
// **The speaker notes are taken, and marked.** "Amortized Analysis — O(1) per operation" is a
// slide; the sentence explaining why is underneath it in the notes pane. Dropping them throws away
// the half of the deck that is prose. But they are the lecturer talking, not the material, and a
// generated answer that quotes an aside as if it were the definition is worse than one that
// doesn't quote it at all — so they land under their own `##` heading, which puts "Speaker notes"
// into the section path and therefore into the context header 13.4 prepends to the indexed text.
@Component
public class PptxExtractor implements DocumentExtractor {

    // The heading level given to a slide title, and the level given to its notes. Two levels apart
    // by one, so the notes sit *under* their slide and the chunker's merge rule sees them as the
    // same top-level unit rather than as two adjacent slides.
    private static final String SLIDE_HEADING = "# ";
    private static final String NOTES_HEADING = "## Speaker notes";

    // Placeholders that carry the slide's title. The title is written first, from the deck's own
    // title element, so these are skipped when the body shapes are walked — otherwise every slide
    // would open with its title twice.
    private static final Set<Placeholder> TITLE_PLACEHOLDERS =
            EnumSet.of(Placeholder.TITLE, Placeholder.CENTERED_TITLE);

    // Furniture, on the slide and in the notes pane alike: the page number in the corner, the date
    // in the footer, the department name in the header. Every slide has them and no slide is about
    // them, so indexing them puts the same six words into every chunk of the deck.
    private static final Set<Placeholder> FURNITURE_PLACEHOLDERS = EnumSet.of(
            Placeholder.SLIDE_NUMBER, Placeholder.DATETIME, Placeholder.FOOTER, Placeholder.HEADER);

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PPTX;
    }

    @Override
    public Extraction extract(byte[] bytes) {
        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            List<XSLFSlide> slides = deck.getSlides();
            List<PageText> pages = new ArrayList<>(slides.size());
            for (int i = 0; i < slides.size(); i++) {
                pages.add(new PageText(i + 1, slideMarkdown(slides.get(i), i + 1)));
            }
            if (pages.isEmpty()) {
                throw new DocumentExtractionException("This presentation has no slides.");
            }
            return Extraction.of(pages);
        } catch (IOException | RuntimeException e) {
            if (e instanceof DocumentExtractionException extraction) {
                throw extraction;
            }
            throw new DocumentExtractionException(
                    "Could not read the presentation. It may be corrupt, password-protected, or "
                            + "saved in an older PowerPoint format.", e);
        }
    }

    private static String slideMarkdown(XSLFSlide slide, int number) {
        StringBuilder markdown = new StringBuilder();
        // Always a heading, even when the slide has no title. A deck with untitled slides would
        // otherwise have no structure at all and fall through to the semantic splitter — and
        // "Slide 7" is still a true, unique, page-numbered boundary, which is what tier 1 needs.
        markdown.append(SLIDE_HEADING).append(heading(slide, number)).append("\n\n");

        for (String block : bodyBlocks(slide)) {
            markdown.append(block).append("\n\n");
        }
        String notes = notesOf(slide);
        if (!notes.isEmpty()) {
            markdown.append(NOTES_HEADING).append("\n\n").append(notes).append("\n\n");
        }
        return markdown.toString().strip();
    }

    private static String heading(XSLFSlide slide, int number) {
        String title = safely(slide::getTitle);
        // The number is kept even when there is a title, because a deck reuses its titles —
        // "Example", "Example", "Summary" — and a section path has to identify one slide.
        return title == null || title.isBlank()
                ? "Slide " + number
                : "Slide " + number + ": " + collapse(title);
    }

    // The slide's shapes in reading order, skipping the title and the furniture.
    //
    // `getShapes()` returns z-order, which is paint order and has nothing to do with reading order:
    // a two-column slide whose right box was drawn first comes back right-then-left. Sorting by the
    // anchor's top edge and then its left edge is the same judgement PDFBox's position sort makes,
    // and it is safe here in a way it is not there — slide shapes are boxes with explicit
    // coordinates, not glyphs whose order the layout engine chose.
    private static List<String> bodyBlocks(XSLFSlide slide) {
        List<XSLFShape> shapes = new ArrayList<>(slide.getShapes());
        shapes.sort(Comparator
                .comparingDouble((XSLFShape shape) -> top(shape))
                .thenComparingDouble(PptxExtractor::left));

        List<String> blocks = new ArrayList<>();
        for (XSLFShape shape : shapes) {
            collect(shape, blocks, true);
        }
        return blocks;
    }

    private static void collect(XSLFShape shape, List<String> blocks, boolean skipTitle) {
        if (shape instanceof XSLFGroupShape group) {
            // A grouped diagram is a container, and its labels are the text on the diagram. Walking
            // into it is what keeps "Insert / Delete / Find" from vanishing off an architecture
            // slide whose boxes happen to be grouped.
            for (XSLFShape child : group.getShapes()) {
                collect(child, blocks, skipTitle);
            }
            return;
        }
        if (shape instanceof XSLFTable table) {
            String rendered = tableMarkdown(table);
            if (!rendered.isEmpty()) {
                blocks.add(rendered);
            }
            return;
        }
        if (!(shape instanceof XSLFTextShape text)) {
            // Pictures, charts and connectors. Nothing here reads them; a picture-bearing slide is
            // what Phase 17.2's visual chunks are for, and it needs no hook in this class.
            return;
        }
        Placeholder placeholder = safely(text::getPlaceholder);
        if (FURNITURE_PLACEHOLDERS.contains(placeholder)
                || (skipTitle && TITLE_PLACEHOLDERS.contains(placeholder))) {
            return;
        }
        blocks.addAll(paragraphBlocks(text));
    }

    // A text box becomes one Markdown block, with its bullets as list items and its indent levels
    // as nesting. Bullets are kept as bullets rather than flattened into prose because they are how
    // a slide states a list of things, and a retriever matching "the three cases" should find the
    // slide that has three of them.
    private static List<String> paragraphBlocks(XSLFTextShape shape) {
        StringBuilder block = new StringBuilder();
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            String line = collapse(paragraph.getText());
            if (line.isEmpty()) {
                continue;
            }
            if (block.length() > 0) {
                block.append('\n');
            }
            if (safely(paragraph::isBullet) == Boolean.TRUE) {
                block.append("  ".repeat(Math.max(0, indent(paragraph)))).append("- ");
            }
            block.append(line);
        }
        return block.length() == 0 ? List.of() : List.of(block.toString());
    }

    // The notes pane, minus the slide-number placeholder PowerPoint puts on every notes page.
    private static String notesOf(XSLFSlide slide) {
        XSLFNotes notes = safely(slide::getNotes);
        if (notes == null) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (XSLFShape shape : notes.getShapes()) {
            // The notes page shows a thumbnail of the slide, whose title placeholder repeats the
            // slide's title. `skipTitle` stays true so it is not written a second time.
            collect(shape, blocks, true);
        }
        return String.join("\n\n", blocks).strip();
    }

    private static String tableMarkdown(XSLFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XSLFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XSLFTableCell cell : row.getCells()) {
                cells.add(collapse(cell.getText()));
            }
            if (cells.stream().anyMatch(cell -> !cell.isEmpty())) {
                rows.add(cells);
            }
        }
        return Tables.markdown(rows);
    }

    private static int indent(XSLFTextParagraph paragraph) {
        Integer level = safely(paragraph::getIndentLevel);
        return level == null ? 0 : level;
    }

    private static double top(XSLFShape shape) {
        Rectangle2D anchor = safely(shape::getAnchor);
        return anchor == null ? Double.MAX_VALUE : anchor.getY();
    }

    private static double left(XSLFShape shape) {
        Rectangle2D anchor = safely(shape::getAnchor);
        return anchor == null ? Double.MAX_VALUE : anchor.getX();
    }

    // Newlines inside a slide's text box are line wrapping, not paragraph structure — a title that
    // the author broke across two lines to fit the box is one title. Collapsing them here keeps a
    // heading on one line, which is what the Markdown heading pattern requires to match at all.
    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    // POI reads a hostile file cooperatively and then throws from a getter — a missing anchor, a
    // notes part that references a slide layout that is not in the package. None of those is a
    // reason to fail a whole deck, so the getters that touch optional structure are called through
    // here and a failure means "this slide does not say".
    private static <T> T safely(java.util.function.Supplier<T> getter) {
        try {
            return getter.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
