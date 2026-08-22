package com.studyloop.backend.document;

// Reads a rendered page image and returns what is on it as Markdown (Phase 15.2).
//
// An interface for the same reason EmbeddingClient and ChatClient are: the tests need a
// deterministic reader that costs nothing and needs no key, and the router needs to be written
// against "a thing that reads pages" rather than against Google's request shape.
//
// **Markdown, not plain text, and that is a pipeline decision rather than a formatting one.**
// Phase 13.2 made Markdown the intermediate representation every extractor converts into, so the
// structural chunker can cut a vision-read page on its own headings exactly as it cuts a
// PDFBox-read one. A VLM that returned a wall of prose would hand the chunker a document with no
// structure and quietly demote it to the semantic tier.
public interface VisionClient {

    // False when no key is configured. The router then scores pages, routes none, and logs what it
    // would have routed — an installation with no vision key keeps ingesting.
    boolean isConfigured();

    // The page as Markdown: headings as `#`, tables as Markdown tables, equations as LaTeX,
    // figures as a described block.
    //
    // `hint` names the defect the gate found, because the instruction that helps depends on it —
    // a scanned page needs transcription, a figure page needs the figure described in words that
    // a text index can match. Throws VisionExtractionException on any failure; the caller decides
    // whether that is fatal.
    String readPage(byte[] pngImage, PageDefect hint);
}
