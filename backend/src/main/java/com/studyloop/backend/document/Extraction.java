package com.studyloop.backend.document;

import java.util.List;

// What every extractor hands the ingestion pipeline, whatever the file was (Phase 16).
//
// The shape is the phase's main claim: a `.pptx`, a `.docx` and a photographed page all arrive as
// **Markdown pages numbered from one**, which is exactly what a PDF has arrived as since 13.2. That
// is why none of chunking, embedding, retrieval, citation, quiz generation or the eval harness had
// to learn that three new formats exist — a slide's title is an `#` heading like any other, so the
// structural splitter cuts on it without being told what a slide is.
//
// `visionPages` was Phase 15's record of what an ingest cost; it stays exactly that, and a
// photographed note is one such page. `blocks` is empty for everything except a handwritten note,
// where the per-block confidences are the thing 16.3 promises to act on rather than discard.
// `images` is Phase 17's addition: the pages this extractor judged to be pictures, rendered, so
// they can be embedded as pictures. Empty for every format that cannot render itself, which is
// the same graceful nothing an unconfigured provider produces.
public record Extraction(List<PageText> pages, int visionPages, List<TranscribedBlock> blocks,
                         List<PageImage> images) {

    public Extraction {
        pages = List.copyOf(pages);
        blocks = List.copyOf(blocks);
        images = List.copyOf(images);
    }

    // A file a text extractor read on its own: no provider call, nothing to show a reviewer.
    public static Extraction of(List<PageText> pages) {
        return new Extraction(pages, 0, List.of(), List.of());
    }

    public static Extraction withVision(List<PageText> pages, int visionPages) {
        return new Extraction(pages, visionPages, List.of(), List.of());
    }

    // The same extraction, plus the pages worth embedding as pictures. Separate from the
    // constructors above so an extractor that has no visual step keeps the call it already had.
    public Extraction withImages(List<PageImage> images) {
        return new Extraction(pages, visionPages, blocks, images);
    }
}
