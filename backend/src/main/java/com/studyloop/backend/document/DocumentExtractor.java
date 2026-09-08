package com.studyloop.backend.document;

// One format's route from bytes to Markdown pages (Phase 16).
//
// Phases 4 through 15 had exactly one of these and did not need the interface: the pipeline called
// `PdfTextExtractor`, and later `PdfExtractionRouter`, by name. Three formats is where naming the
// implementation stops working, and the alternative — a switch on content type inside the
// ingestion orchestrator — would put PowerPoint's object model, Word's style names and a vision
// prompt in the same class as the status machine.
//
// **Each implementation converts; none of them chunks.** The contract is deliberately narrow: a
// list of Markdown pages, one per physical page of whatever "page" means for that format, numbered
// from one. Everything downstream is shared, which is what makes 16.2 nearly free once 16.1 exists
// and what made 16.3 possible at all — a photograph of a page becomes a first-class Document
// because it produces the same thing a PDF does.
public interface DocumentExtractor {

    boolean supports(DocumentFormat format);

    // Throws DocumentExtractionException when the bytes cannot be read or hold no usable text.
    // The orchestrator catches it and records a FAILED document, so the message is written to be
    // read by whoever uploaded the file.
    Extraction extract(byte[] bytes);

    // The same, restricted to a range of the document's pages (Phase 25.1).
    //
    // **A default that ignores the range, so only the PDF router overrides it** — and the default
    // is a statement rather than a shortcut: *this format has no pages to cut before it is read.*
    // A `.docx` genuinely does not; its pages do not exist in the file at all, they are invented by
    // the extractor as it walks paragraphs, so there is nothing to skip and nothing to save. A
    // photographed note is one page by definition.
    //
    // A `.pptx` is the one format where this default is a choice rather than a fact — slides are
    // pages and a range over them would work — and it is left as the default because the feature
    // asked for was a PDF cutter. Overriding it later is one method and no interface change, which
    // is the point of the default living here rather than a second interface existing.
    default Extraction extract(byte[] bytes, PageRange range) {
        return extract(bytes);
    }
}
