package com.studyloop.backend.document;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads a Word document into Markdown (Phase 16.2).
//
// **Better structured than a PDF, and that is the whole reason this is cheap.** Phase 13.2 has to
// infer headings in a PDF from font size, weight and position, and gets them mostly right. A .docx
// does not need inferring: `Heading 1` is a named style the author applied, stored as such in the
// file. So the ladder's tier 1 gets its boundaries from a declaration rather than a guess, and the
// class below is a translation from one vocabulary of structure into another.
//
// **A .docx has no pages, and this does not pretend otherwise.** Word computes page breaks when it
// lays the document out for a particular paper size and font — the file itself carries only the
// breaks the *author* forced. So a page here is "what the author separated with a page break", and
// a document with none is one page. That is a weaker page number than a PDF's, and it is the true
// one: the alternative is numbering sections and calling them pages, which puts "page 4" on a
// citation for something Word would print on page 11.
@Component
public class DocxExtractor implements DocumentExtractor {

    private static final int MAX_HEADING_LEVEL = 6;
    // Word's built-in heading styles are `Heading1`..`Heading9` by id and "heading 1" by name; the
    // id survives localisation and the name does not, so both are matched. `Title` is level 1 —
    // it is the document's own name and everything else sits under it.
    private static final Pattern HEADING_STYLE = Pattern.compile("^heading(\\d)$");

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.DOCX;
    }

    @Override
    public Extraction extract(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<PageText> pages = read(document);
            if (pages.isEmpty()) {
                throw new DocumentExtractionException(
                        "This Word document has no text in it. Text inside images needs the "
                                + "handwritten-notes reader, not this one.");
            }
            return Extraction.of(pages);
        } catch (IOException | RuntimeException e) {
            if (e instanceof DocumentExtractionException extraction) {
                throw extraction;
            }
            throw new DocumentExtractionException(
                    "Could not read the Word document. It may be corrupt, password-protected, or "
                            + "saved in an older Word format.", e);
        }
    }

    private static List<PageText> read(XWPFDocument document) {
        XWPFStyles styles = document.getStyles();
        List<Page> pages = new ArrayList<>();
        pages.add(new Page());

        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFTable table) {
                pages.get(pages.size() - 1).add(tableMarkdown(table));
                continue;
            }
            if (!(element instanceof XWPFParagraph paragraph)) {
                continue;
            }
            // `pageBreakBefore` belongs to the paragraph that follows the break, so the new page
            // has to be opened before this paragraph's own text is written to one.
            if (breaksBefore(paragraph)) {
                pages.add(new Page());
            }
            pages.get(pages.size() - 1).add(paragraphMarkdown(styles, paragraph));
            // A `Ctrl+Enter` break lives in a run *inside* a paragraph, so anything after it in
            // the same paragraph belongs to the next page. Splitting a paragraph's runs across two
            // pages to honour that would cut a sentence in half for a page number; the paragraph
            // stays whole on the page it started, and the next one opens after it.
            if (breaksAfter(paragraph)) {
                pages.add(new Page());
            }
        }

        List<PageText> read = new ArrayList<>();
        for (Page page : pages) {
            String text = page.text();
            if (!text.isBlank()) {
                // Numbered by how many pages have text, not by how many page objects exist: three
                // consecutive page breaks are one break to a reader, and leaving the empty pages
                // in would make every citation after them point two pages further on than it should.
                read.add(new PageText(read.size() + 1, text));
            }
        }
        return read;
    }

    private static String paragraphMarkdown(XWPFStyles styles, XWPFParagraph paragraph) {
        String text = collapse(paragraph.getText());
        if (text.isEmpty()) {
            return "";
        }
        int level = headingLevel(styles, paragraph);
        if (level > 0) {
            return "#".repeat(level) + " " + text;
        }
        if (isListItem(paragraph)) {
            return "  ".repeat(listDepth(paragraph)) + "- " + text;
        }
        return text;
    }

    // The style's id first and its display name second. A document written in a localised Word
    // still carries `Heading2` as the id while naming it "berschrift 2", and one written by
    // LibreOffice or pandoc carries "Heading 2" as the name with an id of its own choosing.
    private static int headingLevel(XWPFStyles styles, XWPFParagraph paragraph) {
        String id = paragraph.getStyleID();
        if (id == null || id.isBlank()) {
            return 0;
        }
        int fromId = levelOf(id);
        if (fromId > 0) {
            return fromId;
        }
        if (styles == null) {
            return 0;
        }
        XWPFStyle style = null;
        try {
            style = styles.getStyle(id);
        } catch (RuntimeException e) {
            // A style id referencing a part the package does not contain. Not a heading, then.
            return 0;
        }
        return style == null ? 0 : levelOf(style.getName());
    }

    private static int levelOf(String styleName) {
        if (styleName == null) {
            return 0;
        }
        String normalised = styleName.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalised.equals("title")) {
            return 1;
        }
        // "Subtitle" is not heading 2 in Word's model — it is a subtitle of the title, and papers
        // use it for a single line under the name. Level 2 is the closest true statement.
        if (normalised.equals("subtitle")) {
            return 2;
        }
        Matcher matcher = HEADING_STYLE.matcher(normalised);
        if (!matcher.matches()) {
            return 0;
        }
        // Word allows nine heading levels; Markdown has six. Levels 7 to 9 are so rarely used that
        // the honest fallback is the deepest heading Markdown can express, rather than dropping the
        // structure entirely and turning the text into a paragraph of the level above.
        return Math.min(Integer.parseInt(matcher.group(1)), MAX_HEADING_LEVEL);
    }

    private static boolean isListItem(XWPFParagraph paragraph) {
        return paragraph.getNumID() != null;
    }

    private static int listDepth(XWPFParagraph paragraph) {
        BigInteger level = paragraph.getNumIlvl();
        return level == null ? 0 : Math.min(level.intValue(), 5);
    }

    private static boolean breaksBefore(XWPFParagraph paragraph) {
        try {
            return paragraph.isPageBreak();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean breaksAfter(XWPFParagraph paragraph) {
        for (XWPFRun run : paragraph.getRuns()) {
            for (CTBr br : run.getCTR().getBrList()) {
                if (br.getType() == STBrType.PAGE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String tableMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(collapse(cell.getText()));
            }
            if (cells.stream().anyMatch(cell -> !cell.isEmpty())) {
                rows.add(cells);
            }
        }
        return Tables.markdown(rows);
    }

    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    // One page's blocks, kept separately so they can be joined by blank lines at the end. Blank
    // lines are not cosmetic here: they are what `StructuralSplitter` splits paragraphs on, so a
    // heading that shares a line with the sentence under it stops being a heading.
    private static final class Page {

        private final List<String> blocks = new ArrayList<>();

        void add(String block) {
            if (block != null && !block.isBlank()) {
                blocks.add(block);
            }
        }

        String text() {
            return String.join("\n\n", blocks);
        }
    }
}
