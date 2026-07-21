package com.studyloop.backend.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Pulls text out of a PDF one page at a time, so each chunk can later be traced back to the
// page it came from (needed for citation-jump in Phase 6). A corrupt or encrypted PDF, or
// one carrying no extractable text (e.g. a scan of images), surfaces as a clear failure.
@Component
public class PdfTextExtractor {

    public List<PageText> extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new PageText(page, stripper.getText(document)));
            }
            return pages;
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "Could not read the PDF. It may be corrupt or password-protected.", e);
        }
    }
}
