package com.studyloop.backend.document;

import java.time.Duration;

// How long an ingest has left, in words (Phase 25.1).
//
// **Every number here is a measurement with a date on it, and that is deliberate.** A constant of
// this kind rots without anyone noticing: on 2026-09-08 the per-page vision cost moved by a factor
// of ten because the model changed, and an estimator carrying an undated "30 seconds a page" would
// have gone on quoting it forever, wrong by an order of magnitude, with nothing in the code to
// suggest it had ever been true.
//
// **What it deliberately does not do is guess how many pages will need the vision model.** That is
// decided by the quality gate, which is 0% of a typeset PDF and 100% of a scan, and no amount of
// arithmetic in front of the gate can tell the two apart. So the estimate before an upload is
// stated as two parts — the part that is proportional to pages, and the part that is per routed
// page — and the single number only appears once the gate has run and the routed count is a fact.
public final class IngestionEstimate {

    // PDFBox text extraction plus the quality gate's two extra text passes and content-stream walk,
    // then chunking and embedding. Measured 2026-09-09 on the 296-page fixture on a laptop; the
    // Render free tier's 0.1 vCPU is slower, which is one reason this is presented as "about".
    private static final double SECONDS_PER_PAGE = 0.25;

    // One vision call, end to end, including the render of the page to PNG at 150 dpi. Measured
    // 2026-09-08 against the same page-120 render on gemini-3.5-flash-lite: 2.6s and 3.2s. Rounded
    // up rather than averaged, because an estimate that runs under is worth more than one that
    // runs over.
    private static final double SECONDS_PER_VISION_PAGE = 4.0;

    // Below this an estimate is noise — "about 3 seconds" invites the reader to time it.
    private static final Duration TOO_SHORT_TO_SAY = Duration.ofSeconds(20);

    private IngestionEstimate() {
    }

    // The whole run, once the routed page count is known.
    public static Duration forDocument(int pages, int visionPages) {
        double seconds = pages * SECONDS_PER_PAGE + visionPages * SECONDS_PER_VISION_PAGE;
        return Duration.ofSeconds(Math.round(seconds));
    }

    // What is left of the vision loop, which is the only part of an ingest long enough to be worth
    // counting down. The pages before and after it are seconds.
    public static Duration forVisionPages(int remainingPages) {
        return Duration.ofSeconds(Math.round(remainingPages * SECONDS_PER_VISION_PAGE));
    }

    // "about 2 min", "about 40s", or null when the honest answer is "you will not notice".
    //
    // Coarse on purpose. A countdown reading "1m 47s" claims a precision this does not have — the
    // per-page cost varies with the page, the provider and whether the free tier is busy — and the
    // reader only wants to know whether to wait or to go and do something else.
    public static String describe(Duration duration) {
        if (duration == null || duration.compareTo(TOO_SHORT_TO_SAY) < 0) {
            return null;
        }
        long seconds = duration.toSeconds();
        if (seconds < 90) {
            return "about " + (Math.round(seconds / 10.0) * 10) + "s";
        }
        long minutes = Math.round(seconds / 60.0);
        return "about " + minutes + " min";
    }
}
