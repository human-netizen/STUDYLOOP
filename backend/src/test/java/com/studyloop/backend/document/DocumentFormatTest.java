package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// What StudyLoop accepts, and why the decision needs its own class (Phase 16).
//
// Two callers ask the same question — the upload endpoint before storing anything, the extractor
// registry after — and the interesting failures here are all about disagreeing with a client. A
// browser reports .pptx three different ways depending on what it knows; a curl invocation reports
// nothing; and a .ppt is not a slightly older .pptx but a different container that would ingest
// with its speaker notes silently missing.
class DocumentFormatTest {

    @Test
    void theProperContentTypeResolves() {
        assertThat(DocumentFormat.of("application/pdf", "lecture.pdf")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.of(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "week4.pptx")).contains(DocumentFormat.PPTX);
        assertThat(DocumentFormat.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "essay.docx")).contains(DocumentFormat.DOCX);
    }

    @Test
    void theExtensionIsAPeerOfTheContentTypeRatherThanAFallback() {
        // A browser that does not recognise the extension sends `application/octet-stream`, and
        // curl sends nothing unless told. Requiring the header would refuse files that are exactly
        // what they claim to be, and the refusal would look arbitrary to whoever hit it.
        assertThat(DocumentFormat.of("application/octet-stream", "week4.pptx"))
                .contains(DocumentFormat.PPTX);
        assertThat(DocumentFormat.of(null, "notes.png")).contains(DocumentFormat.PNG);
        assertThat(DocumentFormat.of("", "essay.docx")).contains(DocumentFormat.DOCX);
    }

    @Test
    void aContentTypeWithParametersIsStillAContentType() {
        // "image/jpeg; charset=binary" is a legal header and not a format name.
        assertThat(DocumentFormat.of("image/jpeg; charset=binary", "photo"))
                .contains(DocumentFormat.JPEG);
        assertThat(DocumentFormat.of("APPLICATION/PDF", "x")).contains(DocumentFormat.PDF);
    }

    @Test
    void bothJpegExtensionsAreTheSameFormat() {
        assertThat(DocumentFormat.of(null, "page.jpg")).contains(DocumentFormat.JPEG);
        assertThat(DocumentFormat.of(null, "page.jpeg")).contains(DocumentFormat.JPEG);
    }

    @Test
    void theStoredTypeIsTheFormatsOwnRatherThanWhateverArrived() {
        // Whatever the client called it, the row records what the bytes are — so the extractor
        // registry and the download endpoint both read a type that describes the file.
        assertThat(DocumentFormat.PPTX.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    void aPreTwoThousandSevenOfficeFileIsRefusedWithSomethingToDoAboutIt() {
        // Not "unsupported". A .ppt is an OLE2 compound file read by a different POI module, and
        // half-supporting it means a deck that ingests with its speaker notes missing and nothing
        // saying so. Telling somebody to press Save As is a better outcome than that.
        assertThatThrownBy(() -> DocumentFormat.require(
                "application/vnd.ms-powerpoint", "week4.ppt", DocumentFormat.materialFormats()))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("Save As")
                .hasMessageContaining("speaker notes");

        assertThatThrownBy(() -> DocumentFormat.require(null, "essay.doc",
                DocumentFormat.materialFormats()))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("Save As");
    }

    @Test
    void anUnknownTypeIsRefusedWithTheListOfWhatIsAccepted() {
        assertThatThrownBy(() -> DocumentFormat.require("text/plain", "notes.txt",
                DocumentFormat.materialFormats()))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("text/plain")
                .hasMessageContaining(".pdf")
                .hasMessageContaining(".pptx")
                .hasMessageContaining(".docx");
    }

    @Test
    void theAllowedListIsPerCallSiteRatherThanGlobal() {
        // A photograph is a personal note: it arrives through a different endpoint, with member
        // rather than manager permissions and owner rather than course visibility. Accepting one
        // as course material would give it neither, so the materials endpoint refuses it even
        // though the format is perfectly well supported elsewhere.
        assertThatThrownBy(() -> DocumentFormat.require("image/png", "page.png",
                DocumentFormat.materialFormats()))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
        assertThat(DocumentFormat.require("image/png", "page.png", DocumentFormat.imageFormats()))
                .isEqualTo(DocumentFormat.PNG);

        // And the other way: a PDF is not a handwritten note.
        assertThatThrownBy(() -> DocumentFormat.require("application/pdf", "lecture.pdf",
                DocumentFormat.imageFormats()))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void everyMaterialFormatHasAnExtractorAndEveryImageFormatDoes() {
        // The registry picks by format, so a constant added here without an extractor would
        // upload cleanly and then fail ingestion — a 202 followed by a FAILED document, for a
        // rule the uploader could have been told at the door.
        List<DocumentExtractor> extractors = List.of(
                new PptxExtractor(),
                new DocxExtractor(),
                new HandwrittenNoteExtractor(null, null));

        for (DocumentFormat format : DocumentFormat.values()) {
            boolean claimed = format == DocumentFormat.PDF
                    || extractors.stream().anyMatch(extractor -> extractor.supports(format));
            assertThat(claimed)
                    .as("no extractor handles %s", format)
                    .isTrue();
        }
    }
}
