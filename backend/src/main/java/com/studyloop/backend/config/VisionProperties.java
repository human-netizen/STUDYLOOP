package com.studyloop.backend.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 15 — the VLM extraction router: when a page is sent to a vision model instead of trusted
// to PDFBox, and what that is allowed to cost.
//
// **Not under `studyloop.retrieval.stages`, and for a sharper reason than chunking's.** A stage
// flag exists so two eval runs can differ by a property; this is read at ingest, so flipping it
// changes nothing about a corpus already in the database. Phase 14's synthetic-queries flag sits
// with the stage flags anyway and had to have an instrument bolted on afterwards — a marker
// counted out of `embed_text` — so that a report headed `synthetic-queries=ON` could not describe
// a corpus built without them. This phase does not need that trick: every document records how
// many of its pages were routed, in `documents.vision_pages`, so the report reads the corpus
// rather than the configuration and cannot be made to lie by forgetting to re-ingest.
//
// **Why a router rather than "send everything to the VLM".** A 300-page textbook is 300 vision
// calls per upload, against a free-tier key and an upload allowance sized for text extraction.
// Routing makes the cost proportional to the defect rate, and turns a design assertion — "we use a
// vision model" — into a measured claim: "X% of this corpus's pages needed one".
@ConfigurationProperties(prefix = "studyloop.vision")
public record VisionProperties(
        // The router itself. Off skips scoring entirely, which is the "before" pipeline: whatever
        // PDFBox produced is what gets indexed, defects and all.
        boolean enabled,
        // Google AI Studio key. Blank leaves the router scoring pages and routing none of them —
        // an installation with no vision key keeps ingesting rather than failing every upload,
        // the same rule the rerank stage and the embedding clients follow.
        String apiKey,
        String model,
        // What the page is rendered at before it is sent. 150 is the floor at which body text in a
        // scanned book is legible to a vision model; 72 (a PDF point) loses the ascenders and 300
        // quadruples the bytes for text that was already readable.
        int dpi,
        // 15.3's guardrail. A per-document ceiling on routed pages, so one scanned 300-page book
        // cannot spend a month's quota inside a single upload. Exceeding it fails the document
        // loudly — see VisionPageCapExceededException for why a truncated READY is worse.
        int maxPagesPerDocument,
        // Scripts this corpus is expected to be written in, for the broken-encoding signal. Unicode
        // script names as Character.UnicodeScript spells them.
        //
        // Configurable because "outside the expected script" is a property of the course, not of
        // the software: a Bangla course would have every page flagged by a Latin-only rule, which
        // is the exact false positive that would make the signal useless where it is most needed.
        // COMMON covers digits, punctuation and maths operators; GREEK is here because a computer
        // science textbook is full of Theta and epsilon and neither is a defect.
        List<String> expectedScripts,
        Thresholds thresholds
) {

    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final int DEFAULT_DPI = 150;
    private static final int DEFAULT_MAX_PAGES = 40;
    // Public because the gate falls back to it when every configured name turns out to be a
    // typo. Falling back to "nothing" there would make every character on every page foreign
    // and route the whole upload to a vision model over a misspelling.
    public static final List<String> DEFAULT_SCRIPTS =
            List.of("LATIN", "COMMON", "GREEK", "INHERITED");

    public VisionProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (dpi <= 0) {
            dpi = DEFAULT_DPI;
        }
        if (maxPagesPerDocument <= 0) {
            maxPagesPerDocument = DEFAULT_MAX_PAGES;
        }
        if (expectedScripts == null || expectedScripts.isEmpty()) {
            expectedScripts = DEFAULT_SCRIPTS;
        }
        if (thresholds == null) {
            thresholds = Thresholds.defaults();
        }
    }

    // The router is only live when it is switched on *and* has somewhere to send a page. Both
    // halves are checked at the one call site, so a blank key is a quiet no-op rather than an
    // upload failure.
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public static VisionProperties defaults() {
        return new VisionProperties(true, null, null, 0, 0, null, null);
    }

    // Where each of the four signals tips from "this page extracted fine" to "PDFBox is guessing".
    //
    // Every one of them is a number somebody has to be able to argue with, which is why they are
    // configuration and not constants: the right cut for a scanned lecture handout is not the right
    // cut for a typeset textbook, and a reader who thinks a threshold is wrong should be able to
    // move it and re-run the fixture report rather than rebuild.
    public record Thresholds(
            // Characters extracted per 1,000 square points of page. A US Letter page is ~485 of
            // those units and an ordinary page of prose yields 4-6 characters per unit, so 0.5 is
            // "this page produced under a tenth of the text a page of writing produces".
            double minCharsPerThousandPoints,
            // Fraction of the page covered by image XObjects. Low text *and* a page-filling image
            // is the signature of a scan; low text and no image is a blank page or a chapter
            // opener, and sending those to a vision model buys nothing.
            double scannedImageCoverage,
            // A figure page is the same shape with the dial turned down: a real diagram occupying a
            // quarter of the page, and enough text around it to prove extraction is working. The
            // extracted text is fine and it is not what the page is about, which is exactly the
            // failure Phase 13's eval kept surfacing as the weakest question kind.
            double figureImageCoverage,
            // The same judgement for a figure that was *drawn* rather than pasted, counted in path
            // segments. This threshold is not in the plan and the corpus is why it exists: Open
            // Data Structures contains zero image XObjects across all 306 of its pages, because a
            // LaTeX book emits its diagrams as vector artwork — so the coverage rule above could
            // never fire on the very corpus whose figure questions have been the weakest column in
            // the golden set since Phase 12. A heading rule is one or two segments; a tree diagram
            // is hundreds.
            int figureVectorSegments,
            double figureCharsPerThousandPoints,
            // Fraction of non-space characters belonging to no expected script. A broken ToUnicode
            // CMap does not fail — it succeeds and returns the wrong codepoints, usually from the
            // private use area, so the page has the right shape and unreadable content.
            double maxForeignRatio,
            // How much of the page PDFBox's position-sorted reading and its content-stream reading
            // cannot agree to read in the same order. Single-column prose measures 0.00; a
            // two-column page measures about 0.44, because the longest order both readings agree
            // on is one whole column and the other column is what is left over. 0.35 sits in that
            // gap. Half is the natural ceiling for two columns, so a threshold near 1 would be
            // asking for a page that cannot exist.
            double maxOrderDisagreement
    ) {

        private static final double DEFAULT_MIN_CHARS = 0.5;
        private static final double DEFAULT_SCANNED_COVERAGE = 0.5;
        private static final double DEFAULT_FIGURE_COVERAGE = 0.25;
        // Measured against the fixture corpus rather than guessed: a page of prose with a rule or
        // two under its heading sits in the low tens, and the pages holding a real diagram sit an
        // order of magnitude above that. See FixtureCorpusTest for the distribution it was read off.
        private static final int DEFAULT_FIGURE_VECTOR_SEGMENTS = 150;
        private static final double DEFAULT_FIGURE_CHARS = 2.0;
        private static final double DEFAULT_MAX_FOREIGN = 0.30;
        private static final double DEFAULT_MAX_DISAGREEMENT = 0.35;

        public Thresholds {
            if (minCharsPerThousandPoints <= 0) {
                minCharsPerThousandPoints = DEFAULT_MIN_CHARS;
            }
            if (scannedImageCoverage <= 0) {
                scannedImageCoverage = DEFAULT_SCANNED_COVERAGE;
            }
            if (figureImageCoverage <= 0) {
                figureImageCoverage = DEFAULT_FIGURE_COVERAGE;
            }
            if (figureVectorSegments <= 0) {
                figureVectorSegments = DEFAULT_FIGURE_VECTOR_SEGMENTS;
            }
            if (figureCharsPerThousandPoints <= 0) {
                figureCharsPerThousandPoints = DEFAULT_FIGURE_CHARS;
            }
            // A ratio at or above 1 can never be reached — every character would have to be
            // foreign — so it is not a threshold, it is the signal switched off by a typo.
            if (maxForeignRatio <= 0 || maxForeignRatio >= 1.0) {
                maxForeignRatio = DEFAULT_MAX_FOREIGN;
            }
            if (maxOrderDisagreement <= 0 || maxOrderDisagreement >= 1.0) {
                maxOrderDisagreement = DEFAULT_MAX_DISAGREEMENT;
            }
        }

        public static Thresholds defaults() {
            return new Thresholds(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
