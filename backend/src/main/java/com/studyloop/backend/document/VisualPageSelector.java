package com.studyloop.backend.document;

import com.studyloop.backend.config.VisualProperties;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Phase 17.2 — which pages are worth embedding as pictures, and rendering them.
//
// **This reuses Phase 15's measurements to answer a different question, and the difference is the
// whole phase.** The quality gate scores every page for image coverage and path segments in order
// to decide whether PDFBox's *text* can be trusted; the answer it gives for a dense textbook page
// with a tree diagram on it is "yes, the text is fine", and it is right. It is also the page whose
// diagram nothing in the index can see. So the same two numbers are read again here without the
// gate's sparse-text requirement, which is exactly the clause that made a figure invisible to
// Phase 15 whenever there was prose around it.
//
// The cost of asking is nothing: `PageQualityGate.score` already walked every content stream, so
// this is a filter over numbers that exist. What costs is the rendering and the embedding, and
// those happen only for the pages that pass.
//
// **No provider call lives in this class**, which is why it needs no key of its own and no
// fallback: rendering is PDFBox drawing its own file. What it does ask the embedding provider is
// whether it can take a picture at all, and it renders nothing when the answer is no — see
// enabled() for why that is better than writing rows nothing can reach.
@Component
@RequiredArgsConstructor
public class VisualPageSelector {

    private static final Logger log = LoggerFactory.getLogger(VisualPageSelector.class);

    private final PageImageRenderer renderer;
    private final VisualProperties properties;
    private final EmbeddingClient embeddingClient;

    // Whether anything here will run. Read by the extraction router, which pays for the scoring
    // pass and should not pay for it when neither phase that reads it is switched on.
    //
    // **Gated on the embedder's capability as well as on the switch**, so a deployment running
    // Google's text-embedding-004 or a local Ollama model renders nothing and writes nothing. The
    // alternative is worse than wasteful: rows whose vector is null are a second copy of every
    // figure page's text that no query can ever reach, sitting in the same table as the chunks
    // that can. A picture nothing can embed is not a retrievable thing, and should not become one.
    public boolean enabled() {
        return properties.enabled() && embeddingClient.isConfigured()
                && embeddingClient.supportsImages();
    }

    // The pages of this document worth embedding as pictures, in page order.
    //
    // `qualities` comes from the gate the router already ran. Passing it in rather than re-scoring
    // keeps the two decisions provably consistent: one measurement of the page, read twice.
    public List<PageImage> select(PDDocument document, List<PageQuality> qualities) {
        if (!enabled()) {
            return List.of();
        }
        List<PageQuality> pictures = qualities.stream().filter(this::looksLikeAPicture).toList();
        if (pictures.isEmpty()) {
            return List.of();
        }
        List<PageQuality> kept = capped(pictures);

        PDFRenderer pageRenderer = renderer.rendererFor(document);
        List<PageImage> images = new ArrayList<>(kept.size());
        for (PageQuality quality : kept) {
            try {
                images.add(new PageImage(quality.pageNumber(),
                        renderer.renderPng(pageRenderer, quality.pageNumber(), properties.dpi()),
                        quality.vectorSegments(), quality.imageCoverage()));
            } catch (RuntimeException e) {
                // Not fatal, and this is the one place in the extraction path where that is the
                // easy call. A page that will not render loses its visual chunk; its text chunks
                // are untouched and the document is exactly as answerable as it was in Phase 16.
                // Failing the upload would trade a working document for a missing picture.
                log.warn("Could not render page {} for a visual chunk: {}",
                        quality.pageNumber(), e.getMessage());
            }
        }
        log.info("Visual chunks: {} of {} pages are pictures{}",
                images.size(), qualities.size(),
                kept.size() < pictures.size()
                        ? " (%d qualified, capped at %d)".formatted(
                                pictures.size(), properties.maxPagesPerDocument())
                        : "");
        return List.copyOf(images);
    }

    // Either way of putting a picture on a page, and deliberately without the gate's requirement
    // that the page also be short of text. That clause is right for Phase 15 — a page with a
    // diagram and a full column of prose has already told a text index most of what it knows — and
    // wrong here, because "most of what it knows" is the part that was never the problem.
    private boolean looksLikeAPicture(PageQuality quality) {
        return quality.imageCoverage() >= properties.minImageCoverage()
                || quality.vectorSegments() >= properties.minVectorSegments();
    }

    // Keeps the busiest pages when a document has more figures than the budget allows.
    //
    // Truncating in page order would spend a diagram-heavy chapter's whole budget on its first
    // half, which is a worse answer than any ranking. Sorted by drawing density, restored to page
    // order afterwards so the chunk indices below run forward.
    private List<PageQuality> capped(List<PageQuality> pictures) {
        if (pictures.size() <= properties.maxPagesPerDocument()) {
            return pictures;
        }
        return pictures.stream()
                .sorted(Comparator
                        .comparingDouble(PageQuality::imageCoverage)
                        .thenComparingInt(PageQuality::vectorSegments)
                        .reversed())
                .limit(properties.maxPagesPerDocument())
                .sorted(Comparator.comparingInt(PageQuality::pageNumber))
                .toList();
    }
}
