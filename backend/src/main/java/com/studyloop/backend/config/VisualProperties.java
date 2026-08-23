package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 17 — which pages get embedded as pictures, and what that is allowed to cost.
//
// **Separate from `studyloop.vision`, which is the phase before it, and the difference is worth
// stating because the two read the same measurements.** Phase 15 asks "did PDFBox fail on this
// page", and routes the failures to a model so the *text* is right. Phase 17 asks "is there a
// picture on this page", and embeds those pages so the picture is *findable*. A page can be either,
// both or neither: a scanned page fails extraction and is a picture; a dense textbook page with a
// tree diagram on it extracts perfectly and is still a page whose diagram no text index can see.
// That last case is the one this phase exists for, and it is precisely the case Phase 15's gate
// declines — correctly, because its job was the text.
//
// **Not under `studyloop.retrieval.stages` either, for the reason Phase 15 gave: this is read at
// ingest.** Flipping it changes nothing about a corpus already in the database — the visual chunks
// are rows, and rows are written once. `stages.visual` next door is the query-time half, and the
// two are kept apart so the eval can measure a corpus that *has* visual chunks with the fourth
// list off, which is the only honest baseline for turning it on.
@ConfigurationProperties(prefix = "studyloop.visual")
public record VisualProperties(

        // Whether ingestion writes visual chunks at all. Off reproduces the Phase 16 corpus
        // exactly: text chunks and nothing else.
        boolean enabled,

        // What a page is rendered at before it is embedded, and deliberately lower than the 150
        // the vision extractor uses.
        //
        // The two numbers answer different questions. 150 is the floor at which a model can *read*
        // body text in a scanned book, and reading is what Phase 15 asks of it. Here the model is
        // asked what the page looks like, and embed-v4.0 downsamples whatever it is given onto its
        // own tile grid before it ever sees a glyph — so DPI above that grid buys bytes on the wire
        // and image tokens on the bill and no new information. 120 keeps a US Letter page inside
        // 1250 pixels on the long edge, which is under the resize threshold and about a third of
        // the bytes of a 150 DPI render.
        int dpi,

        // A per-document ceiling on visual chunks, the sibling of `vision.max-pages-per-document`
        // and set higher on purpose. Exceeding it is not an error here: the pages are ranked by how
        // much is drawn on them and the busiest ones are kept, because a diagram-heavy chapter is
        // ordinary material rather than the pathological upload the vision cap guards against, and
        // refusing it would take a working document away over a picture budget.
        int maxPagesPerDocument,

        // Share of the page covered by image XObjects, at or above which the page is a picture.
        // Lower than the vision router's figure threshold, because that one is paired with a
        // requirement that the page be sparse and this one is not: a full page of prose with a
        // quarter-page photograph on it is exactly the page whose photograph is unsearchable.
        double minImageCoverage,

        // The same judgement for a figure that was *drawn* rather than pasted, in path segments.
        // Kept in step with `vision.thresholds.figure-vector-segments` and for the same measured
        // reason: the fourteen fixture chapters contain zero image XObjects across all 306 pages,
        // because a LaTeX book emits its diagrams as vector artwork. A rule under a heading is one
        // or two segments; a tree diagram is hundreds.
        int minVectorSegments
) {

    private static final int DEFAULT_DPI = 120;
    private static final int DEFAULT_MAX_PAGES = 60;
    private static final double DEFAULT_MIN_IMAGE_COVERAGE = 0.15;
    private static final int DEFAULT_MIN_VECTOR_SEGMENTS = 150;

    public VisualProperties {
        if (dpi <= 0) {
            dpi = DEFAULT_DPI;
        }
        if (maxPagesPerDocument <= 0) {
            maxPagesPerDocument = DEFAULT_MAX_PAGES;
        }
        // A coverage at or above 1 can never be reached, so it is not a strict setting — it is the
        // raster half of the signal switched off by a typo.
        if (minImageCoverage <= 0 || minImageCoverage >= 1.0) {
            minImageCoverage = DEFAULT_MIN_IMAGE_COVERAGE;
        }
        if (minVectorSegments <= 0) {
            minVectorSegments = DEFAULT_MIN_VECTOR_SEGMENTS;
        }
    }

    public static VisualProperties defaults() {
        return new VisualProperties(true, 0, 0, 0, 0);
    }
}
