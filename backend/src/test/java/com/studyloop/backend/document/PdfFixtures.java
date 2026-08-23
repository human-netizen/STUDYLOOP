package com.studyloop.backend.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

// Opens a PDF built by TestPdfs, runs something against it, and closes it.
//
// Small, but it exists because a leaked PDDocument in a test suite is invisible until the day a
// fixture gets large: PDFBox holds the file's parsed object graph, and the tests here build one
// per assertion.
final class PdfFixtures {

    private PdfFixtures() {
    }

    static <T> T withDocument(byte[] pdfBytes, Function<PDDocument, T> work) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return work.apply(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
