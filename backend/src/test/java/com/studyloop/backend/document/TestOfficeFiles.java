package com.studyloop.backend.document;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

// Real .pptx and .docx files, built in memory, for the Phase 16 extractor tests.
//
// Built rather than committed, for the same reason TestPdfs builds its PDFs: a fixture whose
// interesting property is a *difference* — this slide has speaker notes and that one does not,
// this paragraph carries a page break and that one does not — is only readable when the two are
// one line apart in source. A committed binary hides which byte the test is actually about, and
// there is no way to tell from looking at it whether the property still holds.
final class TestOfficeFiles {

    private TestOfficeFiles() {
    }

    // ── PowerPoint ──────────────────────────────────────────────────────────────────────────

    static Deck deck() {
        return new Deck();
    }

    static final class Deck {

        private final XMLSlideShow show = new XMLSlideShow();

        // A slide with a title, bullet body text, and optionally speaker notes.
        Deck slide(String title, List<String> bullets, String notes) {
            XSLFSlide slide = show.createSlide(layout(SlideLayout.TITLE_AND_CONTENT));
            if (title != null) {
                XSLFTextShape titleShape = slide.getPlaceholder(0);
                titleShape.setText(title);
            }
            XSLFTextShape body = slide.getPlaceholder(1);
            body.clearText();
            for (String bullet : bullets) {
                XSLFTextParagraph paragraph = body.addNewTextParagraph();
                paragraph.setBullet(true);
                paragraph.addNewTextRun().setText(bullet);
            }
            if (notes != null) {
                notesBody(slide).setText(notes);
            }
            return this;
        }

        // A slide with no title placeholder filled in at all.
        Deck untitled(String body) {
            XSLFSlide slide = show.createSlide(layout(SlideLayout.BLANK));
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new Rectangle(50, 50, 400, 100));
            box.setText(body);
            return this;
        }

        // Two text boxes whose z-order is the reverse of their reading order: the second column is
        // drawn first. This is the normal case in a real deck, not a contrivance — PowerPoint
        // paints in creation order and authors rarely create left to right.
        Deck twoColumns(String left, String right) {
            XSLFSlide slide = show.createSlide(layout(SlideLayout.BLANK));
            XSLFTextBox second = slide.createTextBox();
            second.setAnchor(new Rectangle(400, 100, 300, 100));
            second.setText(right);
            XSLFTextBox first = slide.createTextBox();
            first.setAnchor(new Rectangle(50, 100, 300, 100));
            first.setText(left);
            return this;
        }

        Deck tableSlide(String title, List<List<String>> rows) {
            XSLFSlide slide = show.createSlide(layout(SlideLayout.TITLE_ONLY));
            slide.getPlaceholder(0).setText(title);
            XSLFTable table = slide.createTable();
            table.setAnchor(new Rectangle(50, 150, 500, 200));
            for (List<String> cells : rows) {
                XSLFTableRow row = table.addRow();
                for (String cell : cells) {
                    row.addCell().setText(cell);
                }
            }
            return this;
        }

        byte[] bytes() {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                show.write(out);
                show.close();
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private XSLFSlideLayout layout(SlideLayout wanted) {
            return show.getSlideMasters().get(0).getLayout(wanted);
        }

        private XSLFTextShape notesBody(XSLFSlide slide) {
            XSLFNotes notes = show.getNotesSlide(slide);
            for (XSLFShape shape : notes.getShapes()) {
                if (shape instanceof XSLFTextShape text
                        && text.getPlaceholder() == Placeholder.BODY) {
                    return text;
                }
            }
            XSLFTextBox box = notes.createTextBox();
            box.setAnchor(new Rectangle(50, 400, 500, 200));
            return box;
        }
    }

    // ── Word ────────────────────────────────────────────────────────────────────────────────

    static Doc doc() {
        return new Doc();
    }

    static final class Doc {

        private final XWPFDocument document = new XWPFDocument();

        Doc heading(String styleId, String text) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setStyle(styleId);
            paragraph.createRun().setText(text);
            return this;
        }

        Doc paragraph(String text) {
            document.createParagraph().createRun().setText(text);
            return this;
        }

        // A list item is a paragraph with a numbering reference, which is what Word writes for
        // both bullets and numbers — the difference is in the numbering definition, not here.
        Doc listItem(String text, int level) {
            XWPFParagraph paragraph = document.createParagraph();
            CTPPr properties = paragraph.getCTP().isSetPPr()
                    ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
            CTNumPr numbering = properties.addNewNumPr();
            CTDecimalNumber id = numbering.addNewNumId();
            id.setVal(BigInteger.ONE);
            CTDecimalNumber indent = numbering.addNewIlvl();
            indent.setVal(BigInteger.valueOf(level));
            paragraph.createRun().setText(text);
            return this;
        }

        // Ctrl+Enter: a break inside a run, which is how a person actually forces a new page.
        Doc pageBreak() {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.addBreak(BreakType.PAGE);
            return this;
        }

        Doc table(List<List<String>> rows) {
            XWPFTable table = document.createTable(rows.size(), rows.get(0).size());
            for (int r = 0; r < rows.size(); r++) {
                for (int c = 0; c < rows.get(r).size(); c++) {
                    table.getRow(r).getCell(c).setText(rows.get(r).get(c));
                }
            }
            return this;
        }

        byte[] bytes() {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                document.write(out);
                document.close();
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
