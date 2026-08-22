package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final PdfTextExtractor textExtractor;
    private final PageQualityGate qualityGate;
    private final PageImageRenderer renderer;
    private final VisionClient visionClient;
    private final VisionProperties properties;

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
    @Override
    public Extraction extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            List<PageText> pages = textExtractor.extract(document);
            if (!properties.enabled()) {
                return Extraction.of(pages);
            }
            return route(document, pages);
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "Could not read the PDF. It may be corrupt or password-protected.", e);
        }
    }

    private Extraction route(PDDocument document, List<PageText> pages) {
        List<PageQuality> qualities = qualityGate.score(document);
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

    // One page, with a bounded wait for a rate limit and no wait for anything else. A 429 during a
    // fifty-page scan is the expected case on a free-tier key, and it is the one failure that
    // resolves itself by doing nothing.
    private String readWithRetries(byte[] png, PageQuality quality) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                return visionClient.readPage(png, quality.defect());
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt == MAX_RATE_LIMIT_RETRIES || !isRateLimited(e)) {
                    break;
                }
                long wait = RETRY_WAIT.toMillis() * (attempt + 1);
                log.warn("Rate-limited reading page {}; waiting {}s and retrying ({}/{})",
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

    private static boolean isRateLimited(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("429") || lower.contains("rate limit") || lower.contains("quota");
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
