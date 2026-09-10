package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Phase 15 — extract with PDFBox, score every page, and send only the failures to a vision model.
//
// This is the seam the whole phase turns on. Before it, "extraction" was one call and one outcome;
// now it is a decision per page, and the decision is measured rather than assumed. What the
// pipeline downstream sees is unchanged — a list of Markdown pages, one per physical page, numbered
// from one — which is why nothing in chunking, embedding, citation or retrieval had to learn that
// vision exists.
//
// **The cost argument, which is the reason the router exists at all.** Sending every page to a VLM
// is a design assertion; sending the failures is a number. On the fourteen-chapter fixture corpus
// the gate routes essentially nothing, so the feature costs nothing on material that does not need
// it — and on a scanned book it costs one call per page, which is what the per-document cap is for.
//
// **Failure is fatal here, unlike the summary.** A document whose summary failed is fully usable.
// A document whose scanned pages silently kept their empty text reaches READY and cannot answer a
// question about half of itself, with no symptom anywhere. That is the same argument Phase 14 made
// about a half-generated corpus, and it is why a vision failure fails the upload.
//
// The one deliberate exception is an *unconfigured* client: a deployment with no vision key must
// keep ingesting the documents it was ingesting yesterday, so the router scores, routes nothing,
// and logs what it would have routed.
@Component
@RequiredArgsConstructor
public class PdfExtractionRouter implements DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionRouter.class);

    // The same bounded wait Phase 14 gave synthetic queries, for the same reason: ingestion is
    // asynchronous behind a status machine and nobody is holding a connection open, so a minute
    // asleep is cheaper than a failed document. Linear rather than exponential — the window being
    // waited out is a fixed minute, and doubling would spend the later attempts asleep long after
    // it reset.
    //
    // It lives in the router rather than in the client for the same reason too: the client is
    // "bytes in, Markdown out" and should stay usable by a caller who cannot afford to wait.
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration RETRY_WAIT = Duration.ofSeconds(20);
    // A server-side blip is not a quota window and must not be waited out like one. Gemini's own
    // 503 says "spikes in demand are usually temporary"; seconds is the right order, and on a
    // 0.1 vCPU instance three pages each sleeping a minute is its own outage.
    private static final Duration TRANSIENT_RETRY_WAIT = Duration.ofSeconds(3);

    private final PdfTextExtractor textExtractor;
    private final PageQualityGate qualityGate;
    private final PageImageRenderer renderer;
    private final VisionClient visionClient;
    private final VisionProperties properties;
    private final VisualPageSelector visualPageSelector;

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PDF;
    }

    // Returns the pages as before, plus how many of them a vision model read.
    //
    // The count is returned rather than logged because it is stored — `documents.vision_pages` —
    // and being stored is what lets the eval report describe the corpus from the corpus. Phase 14's
    // synthetic-query flag had no such record and needed a marker string counted out of embed_text
    // to stop a report claiming a pipeline the corpus was not built with.
    //
    // Phase 16 moved the return type out of this class: three more formats produce the same thing,
    // and a shared `Extraction` is what lets the ingestion orchestrator stay format-blind.
    // Phase 17 added a second reader of the same measurements, which is why the scoring pass moved
    // up here. The gate asks "can PDFBox's text be trusted on this page" and the visual selector
    // asks "is there a picture on this page"; those are different questions with a shared answer
    // sheet, and scoring twice would let the two disagree about the same page.
    @Override
    public Extraction extract(byte[] pdfBytes) {
        return extract(pdfBytes, PageRange.all());
    }

    // Phase 25.1 — the same routing over a slice of the document.
    //
    // The range is pushed down into both readers rather than applied to their results, because the
    // saving is the point: the quality gate runs three passes per page and the vision loop renders
    // and uploads one image per routed page, and neither should touch a page nobody asked to
    // ingest. What comes back still carries the source document's own page numbers — see PageRange
    // for why renumbering would be a citation bug with no symptom at ingest.
    @Override
    public Extraction extract(byte[] pdfBytes, PageRange range) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            List<PageText> pages = textExtractor.extract(document, range);
            if (!properties.enabled() && !visualPageSelector.enabled()) {
                return Extraction.of(pages);
            }
            List<PageQuality> qualities = qualityGate.score(document, range);
            Extraction extraction = properties.enabled()
                    ? route(document, pages, qualities)
                    : Extraction.of(pages);
            return extraction.withImages(visualPageSelector.select(document, qualities));
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "Could not read the PDF. It may be corrupt or password-protected.", e);
        }
    }

    private Extraction route(PDDocument document, List<PageText> pages, List<PageQuality> qualities) {
        List<PageQuality> failing = qualities.stream().filter(PageQuality::needsVision).toList();
        if (failing.isEmpty()) {
            return Extraction.of(pages);
        }

        // Logged at the count and listed at the page, because both questions get asked: "how much
        // of this document was unreadable" when the bill arrives, and "which page, and why" when
        // somebody disagrees with a threshold.
        log.info("Extraction quality: {} of {} pages need the vision model ({})",
                failing.size(), pages.size(), summarise(failing));
        for (PageQuality quality : failing) {
            log.debug("  {}", quality.describe());
        }

        if (!visionClient.isConfigured()) {
            // Not a failure. The alternative — refusing every document with one bad page because
            // no vision key is set — takes uploads away from an installation that never asked for
            // this phase. It is logged at WARN because a corpus indexed this way is quietly worse
            // at being searched, and nothing else would ever say so.
            log.warn("{} of {} pages extracted badly and no vision key is configured; "
                            + "indexing them as extracted", failing.size(), pages.size());
            return Extraction.of(pages);
        }
        if (failing.size() > properties.maxPagesPerDocument()) {
            throw new VisionPageCapExceededException(
                    failing.size(), pages.size(), properties.maxPagesPerDocument());
        }

        // Phase 25.2 — the first thing an uploader is told that a log line used to keep to itself.
        // The routed count is the only honest input to an estimate, and it does not exist until
        // this moment: it is 0% of a typeset PDF and 100% of a scan, and nothing before the gate
        // can tell those apart.
        IngestionProgress.report(IngestionProgress.EXTRACTING_FLOOR,
                sentence("%d of %d pages need the vision model".formatted(failing.size(), pages.size()),
                        IngestionEstimate.describe(IngestionEstimate.forVisionPages(failing.size()))));

        PDFRenderer pageRenderer = renderer.rendererFor(document);
        // Indexed by page number rather than by list position: with a page range in force, `pages`
        // holds pages 12-240 and page 120 is not at index 119.
        Map<Integer, PageText> routed = new LinkedHashMap<>();
        for (PageText page : pages) {
            routed.put(page.pageNumber(), page);
        }

        int degraded = 0;
        int done = 0;
        for (PageQuality quality : failing) {
            IngestionProgress.report(IngestionProgress.EXTRACTING_FLOOR,
                    IngestionProgress.EXTRACTING_CEILING, done, failing.size(),
                    sentence("Reading page %d with the vision model (%d of %d)"
                                    .formatted(quality.pageNumber(), done + 1, failing.size()),
                            IngestionEstimate.describe(
                                    IngestionEstimate.forVisionPages(failing.size() - done))));

            byte[] png = renderer.renderPng(pageRenderer, quality.pageNumber(), properties.dpi());
            String markdown = readOrFallBack(png, quality);
            if (markdown == null) {
                degraded++;
            } else {
                routed.put(quality.pageNumber(), new PageText(quality.pageNumber(), markdown));
            }
            done++;
        }
        // Counted as routed even when it fell back, because `visionPages` records what the ingest
        // *cost* — the call was made and billed — and `degradedPages` records what it got. Folding
        // the two would make a document that spent fifteen calls look like one that spent twelve.
        return Extraction.withVision(List.copyOf(routed.values()), failing.size(), degraded);
    }

    // "Reading page 120 with the vision model (3 of 15) - about 50s left", or just the first half
    // when the remainder is too short to be worth a number. Written for a person: the client
    // renders this string and never parses it.
    private static String sentence(String what, String estimate) {
        return estimate == null ? what + "." : what + " · " + estimate + " left.";
    }

    // One page, with a bounded wait for the failures that pass on their own and none for the rest.
    // A 429 during a fifty-page scan is the expected case on a free-tier key, and it is the one
    // failure that resolves itself by doing nothing; a 5xx is the same kind of answer arriving for
    // a different reason.
    //
    // **Returns null when the page took too long (Phase 25.3), meaning "keep what PDFBox
    // extracted".** Null rather than an exception because a timeout is not a failure of the
    // document — it is one page taking the other branch — and the caller is holding the fallback
    // text already. Every other failure still throws: see below for why an exhausted daily quota in
    // particular must not degrade quietly.
    private String readOrFallBack(byte[] png, PageQuality quality) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                return visionClient.readPage(png, quality.defect());
            } catch (RuntimeException e) {
                lastFailure = e;
                Retry retry = retryKindOf(e);
                if (retry == Retry.TOO_SLOW) {
                    // WARN, not DEBUG: the document is about to be indexed slightly worse than it
                    // could be, and this line plus documents.degraded_pages are the only two places
                    // that will ever say so.
                    log.warn("Page {} did not come back within the vision timeout; "
                                    + "keeping the text PDFBox extracted", quality.pageNumber());
                    return null;
                }
                if (attempt == MAX_RATE_LIMIT_RETRIES || retry == Retry.NEVER) {
                    break;
                }
                Duration base = retry == Retry.RATE_LIMIT ? RETRY_WAIT : TRANSIENT_RETRY_WAIT;
                long wait = base.toMillis() * (attempt + 1);
                log.warn("{} reading page {}; waiting {}s and retrying ({}/{})",
                        retry == Retry.RATE_LIMIT ? "Rate-limited" : "Provider unavailable",
                        quality.pageNumber(), wait / 1000, attempt + 1, MAX_RATE_LIMIT_RETRIES);
                sleep(wait);
            }
        }
        // A quota failure names the quota rather than the page, because the page is not the
        // problem and telling somebody "could not read page 289" sends them to look at page 289.
        String quota = exhaustedQuotaOf(lastFailure);
        if (quota != null) {
            throw new VisionExtractionException(quota, lastFailure);
        }
        throw new VisionExtractionException(
                "The vision extractor could not read page %d (%s)."
                        .formatted(quality.pageNumber(),
                                quality.defect().name().toLowerCase(Locale.ROOT)),
                lastFailure);
    }

    // "3 scanned, 1 figure" — the shape of the problem in one clause, so a log line answers whether
    // this is a scanned book or a textbook with pictures in it.
    private static String summarise(List<PageQuality> failing) {
        Map<PageDefect, Integer> counts = new EnumMap<>(PageDefect.class);
        for (PageQuality quality : failing) {
            counts.merge(quality.defect(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((defect, count) ->
                parts.add(count + " " + defect.name().toLowerCase(Locale.ROOT)));
        return String.join(", ", parts);
    }

    // Whether the provider said "not now" or "not this page" — and how long to wait if the former.
    // Package-private, with retryKindOf below, so the classification can be tested without the
    // waiting: exercising it through extract() would mean a test that really sleeps for two
    // minutes, and the logic worth pinning is which status means which, not that Thread.sleep works.
    // TOO_SLOW is Phase 25.3's, and it is the one kind that is neither retried nor fatal: the page
    // keeps the text PDFBox extracted and the ingest carries on. It is deliberately not folded into
    // TRANSIENT — retrying a call that has already spent the full timeout is three more timeouts,
    // 45 seconds to re-learn what the first 15 established.
    enum Retry { RATE_LIMIT, TRANSIENT, TOO_SLOW, NEVER }

    // **Classified by status code, because the message is not evidence.** Until 2026-09-07 this
    // matched the words "429", "rate limit" and "quota" in the exception text, which made
    // retryability a property of how a provider happened to word an error. Two failures walked
    // straight through it on the same afternoon: a **404** (the pinned model had been retired) and a
    // **503** whose own body read "spikes in demand are usually temporary — please try again later".
    // The first is permanent and the second is the textbook retry, and the string test got both
    // wrong in the same direction — every page failing, and one failed page rejects the document.
    //
    // The client wraps the HTTP failure in a VisionExtractionException, so the chain is walked
    // rather than the top frame tested; the first HTTP-shaped cause decides, and anything with no
    // HTTP cause at all (a parse failure, an empty candidate, a safety block) is not a network
    // problem and is never retried — those return HTTP 200 and retrying them only spends the quota.
    static Retry retryKindOf(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException http) {
                HttpStatusCode status = http.getStatusCode();
                if (status.value() == 429) {
                    // **A 429 is two different answers and the body says which.** Added 2026-09-08,
                    // after a 296-page ingest died on page 289 having successfully transcribed
                    // fourteen pages. Google's error carries a quotaId, and
                    // `GenerateRequestsPerDayPerProjectPerModel-FreeTier` is a *daily* allowance:
                    // waiting 20s, then 40s, then 60s cannot make a day pass, so all two minutes
                    // are guaranteed futile and each attempt spends another request against an
                    // allowance that is already gone. The quota id was in the response body the
                    // whole time; the code was reading the status and throwing the body away.
                    return isDailyQuota(http) ? Retry.NEVER : Retry.RATE_LIMIT;
                }
                return status.is5xxServerError() ? Retry.TRANSIENT : Retry.NEVER;
            }
            // No response at all: connect or read timeout, DNS, a dropped socket.
            if (cause instanceof ResourceAccessException) {
                // A read timeout is the provider being slow, not absent, and Phase 25.3 gives it
                // its own answer. Everything else here fails in milliseconds and genuinely does
                // pass on its own, so it keeps the short retry it had.
                return isTimeout(cause) ? Retry.TOO_SLOW : Retry.TRANSIENT;
            }
        }
        // **A timeout is a timeout whatever Spring wrapped it in.** Added 2026-09-10, after a
        // 260-page ingest died on page 120 with `Read timed out` in the chain and no fallback:
        // `ResourceAccessException` is only used when the *request* failed, and this timeout fired
        // while extracting the *response* — reading the headers to decide a content type — which
        // `DefaultRestClient` reports as a plain `RestClientException`. That is a subclass of
        // neither branch above, so the whole chain fell through to NEVER and one slow page rejected
        // the document, which is precisely the outcome 25.3 exists to prevent.
        //
        // The wrapper is Spring's private business and changes with where in the exchange the clock
        // ran out; the SocketTimeoutException is the fact. So the last question asked is about the
        // fact rather than the wrapper. It stays last, because a real status code is stronger
        // evidence than a socket: a 429 whose body then timed out is still a rate limit.
        return isTimeout(e) ? Retry.TOO_SLOW : Retry.NEVER;
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    // Whether this 429 is a per-day allowance rather than a per-minute rate limit. Matched on the
    // quota id's own wording, which is the provider's structured statement of the contract —
    // "GenerateRequestsPerDayPerProjectPerModel-FreeTier" says per-day *and* per-model, and the
    // second half is why naming a different model is a fresh budget rather than the same one.
    private static boolean isDailyQuota(RestClientResponseException http) {
        String body = http.getResponseBodyAsString();
        return body != null && body.contains("PerDay");
    }

    // The sentence to fail with when the allowance, not the page, is what went wrong. Null when
    // this failure was something else, so the caller keeps its page-shaped message.
    private static String exhaustedQuotaOf(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException http
                    && http.getStatusCode().value() == 429 && isDailyQuota(http)) {
                return ("The vision model's daily free-tier quota is used up, so the rest of this "
                        + "document cannot be read today. Set VISION_MODEL to a different model — "
                        + "the quota is per model, so another one is a fresh allowance — or "
                        + "re-upload tomorrow.");
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new VisionExtractionException("Interrupted while waiting out a rate limit");
        }
    }
}
