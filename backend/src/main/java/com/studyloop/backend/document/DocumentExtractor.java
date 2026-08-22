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
}
