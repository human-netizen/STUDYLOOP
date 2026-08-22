package com.studyloop.backend.document;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.util.List;
import java.util.Set;

// How much of a page is picture rather than prose, counted two ways (Phase 15.1, signal three).
//
// **Why this is not `page.getResources().getXObjectNames().size()`.** The resource dictionary says
// which images the page *may* draw, not how large they are drawn or whether they are drawn at all.
// A 4,000-pixel scan placed as a 20pt icon and the same scan filling the sheet are the same entry
// in that dictionary. Size on the page is a property of the content stream, so the content stream
// is what has to be read.
//
// The mechanism is PDFBox's own interpreter. `Do` paints an XObject through the current
// transformation matrix, and an image XObject is defined on the unit square — so the CTM's two
// scaling factors *are* the placed width and height in points, and their product is the area.
// Nothing here decodes a single pixel: the images are never loaded, only measured.
//
// **Two measures, because the plan's one was blind on the corpus this phase exists for.** 15.1
// specified image coverage, and measured against the fourteen fixture chapters image coverage is
// *exactly zero on all 306 pages* — Open Data Structures draws every one of its figures as vector
// artwork, the way a LaTeX book does. So the signal that was supposed to catch figure pages could
// not fire on the corpus whose figure questions have been the weakest column in the golden set
// since Phase 12. Counting drawing operations catches those: a rule under a heading is one or two
// segments and a tree diagram is hundreds.
//
// Registering `q`, `Q`, `cm` and `gs` is not optional decoration. PDFStreamEngine starts with an
// empty operator table and silently ignores what it does not know, so an engine that skipped them
// would track no CTM at all and measure every image at the identity matrix — one square point
// each, and a scanned page would score as clean.
class PageGraphics extends PDFStreamEngine {

    // Path *construction* operators: move, line, cubic curves, rectangle. Counted rather than the
    // painting operators (`S`, `f`, `B`) because one fill can close a path of two hundred segments,
    // so painting operations measure how the drawing was batched and segments measure how much
    // drawing there is.
    private static final Set<String> PATH_SEGMENTS = Set.of("m", "l", "c", "v", "y", "re");

    private double imageArea;
    private int segments;

    private PageGraphics() {
        addOperator(new Concatenate(this));
        addOperator(new Save(this));
        addOperator(new Restore(this));
        addOperator(new SetMatrix(this));
        addOperator(new SetGraphicsStateParameters(this));
    }

    // What was drawn on the page, as opposed to written on it.
    //
    // `imageCoverage` is capped at 1 because images overlap: a scan laid down in horizontal strips,
    // or a background plus an inset, sums past the sheet, and "1.7 of the page is covered" is not a
    // quantity. The cap costs the signal nothing — everything above the scanned threshold is
    // already the same verdict.
    record Drawing(double imageCoverage, int vectorSegments) { }

    static Drawing of(PDPage page) throws IOException {
        float width = page.getMediaBox().getWidth();
        float height = page.getMediaBox().getHeight();
        PageGraphics engine = new PageGraphics();
        engine.processPage(page);
        double coverage = width <= 0 || height <= 0
                ? 0
                : Math.min(engine.imageArea / (width * height), 1.0);
        return new Drawing(coverage, engine.segments);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        String name = operator.getName();
        if (PATH_SEGMENTS.contains(name)) {
            segments++;
            return;
        }
        if (!"Do".equals(name) || operands.isEmpty() || !(operands.get(0) instanceof COSName xobject)) {
            super.processOperator(operator, operands);
            return;
        }
        PDXObject drawn = getResources() == null ? null : getResources().getXObject(xobject);
        if (drawn instanceof PDImageXObject) {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            // Absolute value: a matrix with a negative scale is a flip, and a flipped image still
            // covers the page it is flipped on.
            imageArea += Math.abs(ctm.getScalingFactorX() * ctm.getScalingFactorY());
        } else if (drawn instanceof PDFormXObject form) {
            // A form is a nested content stream, and both a scanner that wraps its page image in
            // one and a figure emitted as a form would otherwise be invisible here. showForm
            // re-enters with the form's own matrix already concatenated, so the recursion needs no
            // bookkeeping of its own.
            showForm(form);
        }
    }
}
