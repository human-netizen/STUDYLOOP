package com.studyloop.backend.document;

// One page an extractor judged to be a picture, rendered so it can be embedded as one (Phase 17.2).
//
// It travels back with the extracted text rather than being fetched later, because the only place
// that can render a page is the class that already has the file open. Handing it upward keeps
// DocumentIngestionService format-blind: a PDF produces these today and a slide deck could produce
// them tomorrow without the orchestrator learning what either is.
//
// **There is no transcription here, and its absence is a decision.** 17.2 specifies the text half
// of a visual chunk as "the VLM's transcription", and on the pages a VLM read it is exactly that —
// Phase 15 already replaced those pages' text with what the model saw, so it arrives in `pages`
// with everything else. On the pages a VLM did *not* read, the text half is the page's own
// extracted text, because asking a vision model to describe a page whose text Phase 15's gate
// deliberately trusted would undo that phase's entire cost argument to improve the half of this
// chunk that is not the half doing the retrieving.
public record PageImage(

        // 1-based, and the same numbering the text pages use. This is what ties the image back to
        // the page it was rendered from, and what a citation will show.
        int pageNumber,

        // PNG bytes at the visual render DPI. Nothing decodes these on the way through — they
        // exist to be base64'd into one embedding request.
        byte[] png,

        // What made this page look like a picture, for the log line and the fixture report. A
        // page can qualify on either count and a corpus is usually lopsided: a LaTeX textbook is
        // all segments and no images, a slide export the other way round.
        int vectorSegments,
        double imageCoverage
) {
}
