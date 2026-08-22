package com.studyloop.backend.document;

import java.util.Locale;

// What the quality gate measured on one page, and what it concluded (Phase 15.1).
//
// The measurements are carried alongside the verdict rather than being thrown away once the
// decision is made, because the verdict on its own is unfalsifiable. "Page 41 was routed" invites
// no argument; "page 41 extracted 0.02 characters per 1,000 points under an image covering 98% of
// it" says which threshold to move if the router is wrong, and is what the fixture report prints.
public record PageQuality(
        int pageNumber,
        // Extracted characters per 1,000 square points of page area. Normalised by area rather than
        // counted raw so an A5 handout and an A3 poster are judged by the same number.
        double charsPerThousandPoints,
        // Share of non-space characters belonging to no expected script.
        double foreignRatio,
        // Share of the page's area covered by image XObjects, capped at 1. Overlapping images can
        // sum past the page, and a page cannot be more than fully covered.
        double imageCoverage,
        // Path segments drawn on the page. The other half of "is this page a picture", and the one
        // that matters for a book typeset in LaTeX: the fixture corpus has *zero* image XObjects
        // across all 306 of its pages and a figure on many of them, because its diagrams are vector
        // artwork. A rule under a heading is one or two segments; a tree diagram is hundreds.
        int vectorSegments,
        // Share of the page the position-sorted and content-stream readings cannot agree to read in
        // the same order. 0 means the two passes produced the same sequence of words.
        double orderDisagreement,
        // Null when the page extracted cleanly, which is the ordinary case and the one the router
        // is built to leave alone.
        PageDefect defect
) {

    public boolean needsVision() {
        return defect != null;
    }

    // One line of the fixture report (15.4). Fixed width so fourteen documents' worth of routed
    // pages line up under each other and an outlier is visible without reading the numbers.
    public String describe() {
        return String.format(Locale.ROOT,
                "p.%-4d %-16s chars/kpt %6.2f  foreign %5.2f  image %5.2f  vector %5d  order %5.2f",
                pageNumber, defect == null ? "ok" : defect.name().toLowerCase(Locale.ROOT),
                charsPerThousandPoints, foreignRatio, imageCoverage, vectorSegments,
                orderDisagreement);
    }
}
