package com.studyloop.backend.document;

import com.studyloop.backend.config.ChunkingProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 13.2–13.4 — the boundary ladder, on Markdown built here rather than lifted out of a PDF.
//
// The extractor has its own tests; these are about what the chunker does with structure once it
// has it. Every assertion below is a claim the old sliding window could not have satisfied.
class AdaptiveChunkingTest {

    private final TokenCounter tokens = new TokenCounter();
    private final TextChunker chunker = Chunkers.standard();

    private static PageText page(int number, String... blocks) {
        return new PageText(number, String.join("\n\n", blocks));
    }

    // Prose long enough to be worth measuring, and deterministic, so a token count is repeatable.
    // Roughly 17 tokens a sentence, which is what makes the sizes in these tests predictable
    // against a 500-token ceiling and a 120-token merge floor.
    private static String prose(int sentences) {
        return prose(0, sentences);
    }

    // The same, numbered from `from`, for the tests that need two paragraphs that are not the same
    // paragraph.
    private static String prose(int from, int sentences) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < from + sentences; i++) {
            text.append("Sentence number ").append(i)
                    .append(" explains a property of the structure under discussion here. ");
        }
        return text.toString().strip();
    }

    @Test
    void aSectionThatFitsBecomesOneChunkAtItsOwnSize() {
        List<TextChunk> chunks = chunker.chunk(List.of(page(1,
                "# Skiplists",
                "## 4.1 The Basic Structure",
                prose(10),
                "## 4.2 SkiplistSSet",
                prose(10))), "Skiplists");

        // Two sections, two chunks. Neither is padded toward 500 and neither is sliced to reach it,
        // which is the whole difference between a ceiling and a target.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Skiplists > 4.1 The Basic Structure");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("Skiplists > 4.2 SkiplistSSet");
        assertThat(chunks.get(0).tokenCount()).isLessThan(300);
    }

    @Test
    void siblingsAreSiblingsEvenWhenTheDocumentSkipsALevel() {
        // The defect this caught in the real corpus: Open Data Structures opens with "Chapter 4" at
        // one size, the chapter title at a larger one, and every section below at a third — so the
        // trail is two deep when the first `###` arrives. Truncating by depth left both entries in
        // place and filed 4.4 as a subsection of 4.1.
        List<TextChunk> chunks = chunker.chunk(List.of(page(1,
                "# Skiplists",
                "### 4.1 The Basic Structure",
                prose(10),
                "### 4.4 Analysis of Skiplists",
                prose(10))), "Skiplists");

        assertThat(chunks.get(0).sectionPath()).isEqualTo("Skiplists > 4.1 The Basic Structure");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("Skiplists > 4.4 Analysis of Skiplists");
    }

    @Test
    void aSectionOverTheCeilingIsSplitOnParagraphsAndNeverMidSentence() {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("# Sorting");
        for (int i = 0; i < 8; i++) {
            paragraphs.add(prose(6));
        }
        List<TextChunk> chunks = chunker.chunk(
                List.of(page(1, paragraphs.toArray(String[]::new))), "Sorting");

        assertThat(chunks).hasSizeGreaterThan(1);
        for (TextChunk chunk : chunks) {
            assertThat(chunk.tokenCount())
                    .as("chunk %d is over the ceiling", chunk.index())
                    .isLessThanOrEqualTo(ChunkingProperties.defaults().maxTokens());
            // The property the sliding window could never have: every chunk starts where a sentence
            // starts and ends where one ends. The old chunker's boundaries were placed by a counter
            // running out, so most of them fell mid-sentence and some fell mid-word.
            assertThat(chunk.content())
                    .as("chunk %d does not begin and end on sentence boundaries", chunk.index())
                    .matches("(?s)[A-Z].*\\.$");
        }
    }

    @Test
    void nothingIsDuplicatedBetweenNeighbours() {
        // Overlap is gone. It existed to rescue an idea a counter had cut in half; a boundary the
        // author put there does not cut ideas in half, and duplicating text into the index makes
        // two chunks match every query that either of them should have.
        List<TextChunk> chunks = chunker.chunk(List.of(page(1,
                "# Graphs",
                prose(0, 20),
                prose(100, 20),
                prose(200, 20))), "Graphs");

        assertThat(chunks).hasSizeGreaterThan(1);
        String all = String.join("\n", chunks.stream().map(TextChunk::content).toList());
        for (int sentence = 0; sentence < 20; sentence++) {
            // Each sentence exists in exactly one chunk. The old chunker's 60-word overlap meant
            // roughly one word in seven was in the index twice.
            assertThat(occurrences(all, "Sentence number " + sentence + " "))
                    .as("sentence %d", sentence)
                    .isEqualTo(1);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void aFragmentOfASectionMergesWithTheSiblingAfterIt() {
        List<TextChunk> chunks = chunker.chunk(List.of(page(1,
                "# Heaps",
                "## 10.1 Overview",
                "One sentence.",
                "## 10.2 Analysis",
                prose(4))), "Heaps");

        // "10.1 Overview" holding one sentence is not a retrieval unit — its vector would be
        // dominated by whichever three words it happens to contain.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("One sentence.").contains("Sentence number 0");
        // Filed under the heading the two of them agree on, not under the first one's.
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Heaps");
    }

    @Test
    void nothingMergesAcrossATopLevelHeading() {
        List<TextChunk> chunks = chunker.chunk(List.of(page(1,
                "# Chapter 10 Heaps",
                "A single short line about heaps.",
                "# Chapter 11 Sorting",
                "A single short line about sorting.")), "Book");

        // Both are small enough to merge on size alone. They are two chapters, and a chunk spanning
        // the end of one and the start of the next answers questions about neither.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Chapter 10 Heaps");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("Chapter 11 Sorting");
    }

    @Test
    void aChunkRecordsBothEndsOfItsPageSpan() {
        List<TextChunk> chunks = chunker.chunk(List.of(
                page(1, "# Skiplists", prose(6)),
                page(2, prose(6)),
                page(3, "## 4.2 SkiplistSSet", prose(3))), "Skiplists");

        // This is what removes the eval's ±1 page tolerance: the first section genuinely runs
        // across a page break, and now says so instead of being stamped with where it started.
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(2);
        assertThat(chunks.get(1).pageStart()).isEqualTo(3);
        assertThat(chunks.get(1).pageEnd()).isEqualTo(3);
    }

    @Test
    void theEmbeddedTextCarriesTheTitleAndHeadingPathAndTheDisplayedTextDoesNot() {
        TextChunk chunk = chunker.chunk(List.of(page(1,
                "# Skiplists",
                "## 4.4 Analysis",
                "The expected search time is logarithmic.")), "04 skiplists").get(0);

        // The passage on its own is a good passage and a hopeless index entry — what it is about is
        // written two headings above it, and every chapter in the book contains a sentence like it.
        assertThat(chunk.embedText())
                .startsWith("04 skiplists\nSkiplists > 4.4 Analysis\n")
                .endsWith("The expected search time is logarithmic.");
        assertThat(chunk.content()).doesNotContain("04 skiplists");
    }

    @Test
    void theHeaderIsNotStoredWhenItWouldAddNothing() {
        // A null embed_text is what tells the SQL to read `content`. Storing a copy of the passage
        // in a second column to say "no header" would double the table for nothing.
        TextChunk chunk = chunker.chunk(List.of(page(1, "Just a paragraph with no structure at all.")))
                .get(0);

        assertThat(chunk.embedText()).isNull();
        assertThat(chunk.sectionPath()).isNull();
    }

    @Test
    void theTokenCountIsTheContentsNotTheHeaders() {
        TextChunk chunk = chunker.chunk(List.of(page(1,
                "# Skiplists",
                "## 4.4 Analysis",
                prose(3))), "04 skiplists").get(0);

        // Otherwise a 480-token section reports 495 against a ceiling of 500 and reads as a bug to
        // anyone who looks at the column.
        assertThat(chunk.tokenCount()).isEqualTo(tokens.count(chunk.content()));
        assertThat(chunk.tokenCount()).isLessThan(tokens.count(chunk.embedText()));
    }

    @Test
    void chunkIndexesAreContiguousFromZero() {
        List<TextChunk> chunks = chunker.chunk(List.of(page(1,
                "# Trees", prose(30), "## 6.1 BinaryTree", prose(30))), "Trees");

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void aDocumentWithNoExtractableTextIsARefusalNotAnEmptyCorpus() {
        // A scanned PDF extracts to nothing, and the useful outcome is a FAILED document carrying
        // the reason — not a READY one with an empty corpus behind it.
        assertThatThrownBy(() -> chunker.chunk(List.of(page(1, "   "))))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("OCR");
    }

}
