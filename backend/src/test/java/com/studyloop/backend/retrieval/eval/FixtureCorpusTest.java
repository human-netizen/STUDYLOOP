package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.document.Chunkers;
import com.studyloop.backend.document.PageDefect;
import com.studyloop.backend.document.PageQuality;
import com.studyloop.backend.document.PageQualityGate;
import com.studyloop.backend.document.PageText;
import com.studyloop.backend.document.PdfTextExtractor;
import com.studyloop.backend.document.TextChunk;
import com.studyloop.backend.document.TextChunker;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Guards the committed fixture corpus. No Spring, no database, no provider — it reads the PDFs
// off the classpath and pushes them through the same extractor and chunker the ingestion pipeline
// uses, so it runs in CI on every push.
//
// This exists because every number the eval harness prints is computed over these files. If a
// fixture is replaced, truncated, or silently stops extracting — a rebuilt PDF with a different
// font encoding is enough — the metrics would keep printing plausible values against a corpus that
// no longer says what the golden questions claim it says. Better to fail here, loudly, at the
// point the corpus changed.
class FixtureCorpusTest {

    // Sized from the corpus as committed (~200 chunks over 14 chapters). The floor is the number
    // that matters: retrieval returns 6 chunks, so a corpus that shrank to a few dozen would make
    // top-6 cover a large share of everything there is, and every phase after this would score
    // near-perfect for reasons having nothing to do with retrieval quality.
    private static final int MIN_TOTAL_CHUNKS = 150;

    // Phase 15.4. The measured figure is 21 of 306 pages — 6.9% — so the budget is set at roughly
    // half again, which leaves room for the corpus to change without leaving room for a threshold
    // to quietly stop gating. What it is protecting against is drift in the expensive direction: at
    // 6.9% a full re-ingest of this corpus is twenty-one vision calls, and at 30% it is ninety-two
    // and the router has become a vision pipeline with a gate bolted on the front.
    private static final double MAX_ROUTED_SHARE = 0.10;

    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final TextChunker chunker = Chunkers.standard();
    private final PageQualityGate qualityGate = new PageQualityGate(VisionProperties.defaults());

    @Test
    void everyFixtureExtractsRealText() {
        int totalChunks = 0;
        int totalPages = 0;
        StringBuilder report = new StringBuilder("\n===== fixture corpus =====\n");

        for (FixtureDocument fixture : FixtureDocument.values()) {
            List<PageText> pages = extractor.extract(fixture.bytes());
            List<TextChunk> chunks = chunker.chunk(pages);
            totalChunks += chunks.size();
            totalPages += pages.size();

            long words = pages.stream()
                    .mapToLong(page -> page.text().split("\\s+").length)
                    .sum();

            report.append(String.format("%-44s %3d pages %6d words %4d chunks%n",
                    fixture.fileName(), pages.size(), words, chunks.size()));

            assertThat(pages).as("%s extracted no pages", fixture.fileName()).isNotEmpty();
            assertThat(chunks).as("%s produced no chunks", fixture.fileName()).isNotEmpty();

            // A PDF whose font encoding is broken still "extracts" — it yields the right shape
            // and the wrong characters. Checking that ordinary English survives catches that,
            // where a word count alone would not.
            String allText = String.join(" ", pages.stream().map(PageText::text).toList());
            assertThat(allText.toLowerCase())
                    .as("%s extracted no recognisable English — check the font encoding", fixture.fileName())
                    .contains(" the ");
        }

        report.append(String.format("%-44s %3d pages %13s %4d chunks%n",
                "TOTAL (" + FixtureDocument.values().length + " documents)", totalPages, "", totalChunks));
        System.out.println(report);

        assertThat(totalChunks)
                .as("fixture corpus is too small for top-6 retrieval to be a meaningful selection")
                .isGreaterThanOrEqualTo(MIN_TOTAL_CHUNKS);
    }

    @Test
    void pageNumbersRunFromOneAndAreContiguous() {
        // The golden set records expected pages as physical PDF page numbers, and PageGrading
        // compares against the page a chunk stored. Both only mean anything if extraction numbers
        // pages the way the question author counted them.
        for (FixtureDocument fixture : FixtureDocument.values()) {
            List<PageText> pages = extractor.extract(fixture.bytes());
            assertThat(pages.get(0).pageNumber()).isEqualTo(1);
            for (int i = 0; i < pages.size(); i++) {
                assertThat(pages.get(i).pageNumber())
                        .as("%s page index %d", fixture.fileName(), i)
                        .isEqualTo(i + 1);
            }
        }
    }

    // Phase 15.4 — what the VLM extraction router costs on this corpus, and whether it spends it in
    // the right places.
    //
    // This is the measurement the phase exists to be able to state. "We send pages to a vision
    // model" is a design assertion nobody can price; "the router touches 21 of the 306 pages of a
    // real fourteen-chapter textbook, and three of those are pages the golden set already says are
    // hard" is two numbers, and together they are the argument. It runs with no key, no database
    // and no provider: scoring is what decides, and routing is what costs.
    //
    // The budget is an upper bound rather than an equality on purpose. The exact count is a
    // property of this edition of this book, and asserting it would make the test a transcription
    // of today's output; what must not change is the order of magnitude, because a router that
    // crept to a third of the corpus would be a vision pipeline with a gate bolted on.
    @Test
    void theRouterSpendsLittleOnThisCorpusAndSpendsItOnTheHardPages() throws IOException {
        int totalPages = 0;
        Map<PageDefect, Integer> byDefect = new EnumMap<>(PageDefect.class);
        Set<PageRef> routedPages = new HashSet<>();
        StringBuilder report = new StringBuilder(System.lineSeparator()
                + "===== extraction quality (Phase 15.1) =====" + System.lineSeparator());

        for (FixtureDocument fixture : FixtureDocument.values()) {
            List<PageQuality> qualities;
            try (PDDocument document = Loader.loadPDF(fixture.bytes())) {
                qualities = qualityGate.score(document);
            }
            List<PageQuality> routed = qualities.stream().filter(PageQuality::needsVision).toList();
            totalPages += qualities.size();

            List<String> reasons = new ArrayList<>();
            for (PageQuality quality : routed) {
                byDefect.merge(quality.defect(), 1, Integer::sum);
                routedPages.add(PageRef.of(fixture, quality.pageNumber()));
                reasons.add("p." + quality.pageNumber() + " "
                        + quality.defect().name().toLowerCase(Locale.ROOT));
            }
            report.append(String.format("%-44s %3d pages %3d routed  %s%n",
                    fixture.fileName(), qualities.size(), routed.size(), String.join(", ", reasons)));
        }

        int totalRouted = routedPages.size();
        report.append(String.format("%-44s %3d pages %3d routed  (%.1f%%)  %s%n",
                "TOTAL (" + FixtureDocument.values().length + " documents)",
                totalPages, totalRouted, 100.0 * totalRouted / totalPages, byDefect));

        // The other half of the claim, and the one that says the spend is aimed rather than merely
        // small. FIGURE_TABLE has been the weakest question kind in the golden set since Phase 12,
        // and Phase 14 established that no amount of *generated text* moves it — a plot has to be
        // looked at. The gate has never seen the golden set, so any overlap here is the two
        // measurements agreeing independently about which pages are hard.
        report.append(System.lineSeparator())
                .append("--- pages behind the golden set's figure questions ---")
                .append(System.lineSeparator());
        int covered = 0;
        for (GoldenSet.GoldenQuestion question : GoldenSet.load().questions()) {
            if (question.kind() != GoldenSet.Kind.FIGURE_TABLE) {
                continue;
            }
            List<String> hits = new ArrayList<>();
            for (PageRef ref : question.expected()) {
                hits.add(ref + (routedPages.contains(ref) ? " ROUTED" : " -"));
            }
            if (question.expected().stream().anyMatch(routedPages::contains)) {
                covered++;
            }
            report.append(String.format("%-6s %s%n", question.id(), String.join(", ", hits)));
        }
        System.out.println(report);

        assertThat((double) totalRouted / totalPages)
                .as("the quality gate wants a vision model on %d of %d pages of a clean digital "
                        + "textbook — either the corpus changed or a threshold is too loose",
                        totalRouted, totalPages)
                .isLessThanOrEqualTo(MAX_ROUTED_SHARE);

        // Not "all eight". Half of these questions are answerable from a caption or a table that
        // extracts perfectly well as text, and routing those would be spending for nothing. What
        // has to hold is that the router is not orthogonal to the corpus's known weakness.
        assertThat(covered)
                .as("none of the golden set's figure questions points at a page the router would "
                        + "send to a vision model, so this phase cannot be what fixes them")
                .isGreaterThanOrEqualTo(3);
    }
}
