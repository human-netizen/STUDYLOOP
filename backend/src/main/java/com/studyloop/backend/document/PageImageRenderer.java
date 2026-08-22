package com.studyloop.backend.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

// Rasterises one page so a vision model can look at it (Phase 15.2).
//
// The renderer is PDFBox's own — the same code that draws a PDF on screen — so a page that renders
// correctly in a viewer renders correctly here, including the pages whose *text* is unusable. That
// is the whole trick of the phase: a broken /ToUnicode CMap corrupts extraction and cannot corrupt
// rendering, because rendering never consults it.
//
// **PNG, not JPEG.** The subject is small black glyphs on white, which is exactly the content JPEG's
// chroma subsampling and ringing damage most, and a scanned page compresses well as PNG anyway.
// Paying a few hundred kilobytes to not hand the model a blurred serif is the right trade when the
// output is indexed as course material.
//
// One renderer per document rather than per page: PDFRenderer caches font and colour-space work
// across pages, and a scanned book routes every page it has.
@Component
public class PageImageRenderer {

    // PNG bytes for a 1-based page number, rendered at `dpi`.
    //
    // RGB rather than ARGB. A rendered page has nothing transparent on it, and an alpha channel a
    // vision model composites against an unknown background is a way to make white text invisible.
    public byte[] renderPng(PDFRenderer renderer, int pageNumber, int dpi) {
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, dpi, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new VisionExtractionException(
                        "No PNG writer is available to render page " + pageNumber + ".");
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (e instanceof VisionExtractionException already) {
                throw already;
            }
            throw new VisionExtractionException(
                    "Could not render page " + pageNumber + " for the vision extractor: " + e.getMessage(), e);
        }
    }

    public PDFRenderer rendererFor(PDDocument document) {
        return new PDFRenderer(document);
    }
}
