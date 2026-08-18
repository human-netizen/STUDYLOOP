package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.document.Chunkers;
import com.studyloop.backend.document.PageText;
import com.studyloop.backend.document.PdfTextExtractor;
import com.studyloop.backend.document.TextChunk;
import com.studyloop.backend.document.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final TextChunker chunker = Chunkers.standard();

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
}
