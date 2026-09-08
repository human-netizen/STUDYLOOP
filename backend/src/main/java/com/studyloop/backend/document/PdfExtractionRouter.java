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
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
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
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            List<PageText> pages = textExtractor.extract(document);
            if (!properties.enabled() && !visualPageSelector.enabled()) {
                return Extraction.of(pages);
            }
            List<PageQuality> qualities = qualityGate.score(document);
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

        PDFRenderer pageRenderer = renderer.rendererFor(document);
        List<PageText> routed = new ArrayList<>(pages);
        for (PageQuality quality : failing) {
            byte[] png = renderer.renderPng(pageRenderer, quality.pageNumber(), properties.dpi());
            String markdown = readWithRetries(png, quality);
            routed.set(quality.pageNumber() - 1, new PageText(quality.pageNumber(), markdown));
        }
        return Extraction.withVision(routed, failing.size());
    }

    // One page, with a bounded wait for the failures that pass on their own and none for the rest.
    // A 429 during a fifty-page scan is the expected case on a free-tier key, and it is the one
    // failure that resolves itself by doing nothing; a 5xx or a timeout is the same kind of answer
    // arriving for a different reason.
    private String readWithRetries(byte[] png, PageQuality quality) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                return visionClient.readPage(png, quality.defect());
            } catch (RuntimeException e) {
                lastFailure = e;
                Retry retry = retryKindOf(e);
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
    enum Retry { RATE_LIMIT, TRANSIENT, NEVER }

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
                    return Retry.RATE_LIMIT;
                }
                return status.is5xxServerError() ? Retry.TRANSIENT : Retry.NEVER;
            }
            // No response at all: connect or read timeout, DNS, a dropped socket.
            if (cause instanceof ResourceAccessException) {
                return Retry.TRANSIENT;
            }
        }
        return Retry.NEVER;
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
