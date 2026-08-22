package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 16.3 — a photograph of handwritten notes becoming Markdown pages, and the confidence
// threshold that decides what of it reaches the corpus.
//
// The threshold is what these tests are mostly about, because it is the part that can be got
// subtly wrong in a way nothing else would report. Indexing a block the model half-guessed puts a
// plausible wrong sentence into the set that grounds and is cited by future answers; dropping one
// silently makes a gap in somebody's revision notes that they cannot see and will never look for.
// Both are quiet failures, so both are asserted.
class HandwrittenNoteReadingTest {

    private static final double THRESHOLD = 0.6;

    private final FakeVisionClient vision = new FakeVisionClient();

    private HandwrittenNoteExtractor extractor() {
        return new HandwrittenNoteExtractor(vision, properties(THRESHOLD));
    }

    private static VisionProperties properties(double minNoteConfidence) {
        return new VisionProperties(true, "a-key", null, 0, 0, null, null, minNoteConfidence);
    }

    // A minimal but real PNG header, which is all the extractor looks at: it sniffs the bytes
    // rather than trusting the content type, because a phone that labels every file `image/jpeg`
    // would otherwise have Gemini reject the part for disagreeing with its own declaration.
    private static byte[] png() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        return bytes;
    }

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    @Test
    void aConfidentlyReadNoteBecomesOneMarkdownPage() {
        vision.blocks = List.of(
                read("# Quicksort", 0.95),
                read("Partition around a pivot, then recurse on both halves.", 0.91));

        Extraction extraction = extractor().extract(png());

        assertThat(extraction.pages()).hasSize(1);
        assertThat(extraction.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(extraction.pages().get(0).text())
                .isEqualTo("# Quicksort\n\nPartition around a pivot, then recurse on both halves.");
    }

    @Test
    void theNoteIsChunkedByItsOwnHeadingsLikeAnyOtherDocument() {
        // This is the claim the whole sub-phase rests on: closing the loop from a digitised note
        // back into the corpus costs no new retrieval code, because what comes out of the reader
        // is the same thing a PDF produces. So the chunker is run over it, unmodified.
        vision.blocks = List.of(
                read("# Heaps", 0.9),
                read("A binary heap is an almost complete tree.", 0.9),
                read("## Sift down", 0.9),
                read("Swap with the smaller child until the property holds.", 0.9));

        List<SectionBlock> blocks =
                new StructuralSplitter().split(extractor().extract(png()).pages());

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).pathLabel()).isEqualTo("Heaps");
        assertThat(blocks.get(1).pathLabel()).isEqualTo("Heaps > Sift down");
    }

    @Test
    void aBlockBelowTheThresholdIsKeptAndShownButNotIndexed() {
        vision.blocks = List.of(
                read("# Master theorem", 0.94),
                read("T(n) = aT(n/b) + [illegible]", 0.31),
                read("Case 3 applies when the work at the root dominates.", 0.88));

        Extraction extraction = extractor().extract(png());

        // Not indexed: the page text the chunker and the embedder see has no trace of it. A
        // guessed recurrence relation reads exactly like a correct one, and it would be cited
        // back to the student as their own note.
        assertThat(extraction.pages().get(0).text()).doesNotContain("aT(n/b)");
        // Kept and shown: it is still on the page, and the person who wrote it can see that the
        // model could not read this line. A dropped block nobody is told about is indistinguishable
        // from a block that was never there.
        assertThat(extraction.blocks()).hasSize(3);
        assertThat(extraction.blocks().get(1).content()).contains("aT(n/b)");
        assertThat(extraction.blocks().get(1).indexed()).isFalse();
        assertThat(extraction.blocks().get(1).confidence()).isEqualTo(0.31);
        assertThat(extraction.blocks().get(0).indexed()).isTrue();
        assertThat(extraction.blocks().get(2).indexed()).isTrue();
    }

    @Test
    void theDroppedBlockLeavesNoMarkerInTheIndexedText() {
        // It is tempting to write "[1 line could not be read]" into the page so the gap shows up in
        // an answer. That sentence would be embedded, lexically indexed, retrieved and cited as if
        // it were course material. The gap belongs in the review view, which reads the blocks.
        vision.blocks = List.of(read("Readable.", 0.9), read("Scrawl.", 0.1));

        String text = extractor().extract(png()).pages().get(0).text();

        assertThat(text).isEqualTo("Readable.");
        assertThat(text).doesNotContainIgnoringCase("could not be read");
        assertThat(text).doesNotContainIgnoringCase("illegible");
    }

    @Test
    void aBlockExactlyAtTheThresholdIsKept() {
        // The boundary is stated rather than left to whichever comparison somebody typed. Keeping
        // a doubtful block costs one weak passage; dropping a good one costs a note that answers
        // nothing, so the inclusive side is the deliberate one.
        vision.blocks = List.of(read("Right on the line.", THRESHOLD));

        assertThat(extractor().extract(png()).blocks().get(0).indexed()).isTrue();
    }

    @Test
    void aPageWhereNothingCouldBeReadIsAFailureRatherThanAnEmptyDocument() {
        vision.blocks = List.of(read("scrawl", 0.2), read("more scrawl", 0.15));

        assertThatThrownBy(() -> extractor().extract(png()))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("2 blocks")
                // The message says what to do about it, because the fix is almost always the photo.
                .hasMessageContaining("sharper, straighter photo");
    }

    @Test
    void theThresholdIsConfigurableAndTheSamePageAnswersDifferently() {
        vision.blocks = List.of(read("Confident enough for some courses.", 0.7));

        assertThat(new HandwrittenNoteExtractor(vision, properties(0.5))
                .extract(png()).blocks().get(0).indexed()).isTrue();
        assertThatThrownBy(() -> new HandwrittenNoteExtractor(vision, properties(0.9))
                .extract(png()))
                .isInstanceOf(VisionExtractionException.class);
    }

    @Test
    void theImageTypeIsSniffedFromTheBytesRatherThanTrusted() {
        vision.blocks = List.of(read("Anything.", 0.9));

        extractor().extract(png());
        assertThat(vision.mimeTypes).containsExactly("image/png");

        vision.mimeTypes.clear();
        extractor().extract(jpeg());
        assertThat(vision.mimeTypes).containsExactly("image/jpeg");
    }

    @Test
    void bytesThatAreNotAnImageAreRefusedBeforeAnythingIsSpent() {
        byte[] nonsense = "PDF or a renamed zip".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor().extract(nonsense))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("not a PNG or JPEG");
        assertThat(vision.calls).isZero();
    }

    @Test
    void noVisionKeyIsFatalHereUnlikeInThePdfRouter() {
        // The PDF router treats an unconfigured key as a warning: PDFBox already produced text and
        // the vision model was only going to improve it. There is no fallback reading of a
        // photograph — the model *is* the extraction — so continuing would store a document that
        // reports READY with nothing in it.
        vision.configured = false;

        assertThatThrownBy(() -> extractor().extract(png()))
                .isInstanceOf(VisionExtractionException.class)
                .hasMessageContaining("no vision key is configured");
        assertThat(vision.calls).isZero();
    }

    @Test
    void aReadNoteCountsAsOneVisionPage() {
        // `documents.vision_pages` is what the eval report reads to say how a corpus was built.
        // A corpus containing photographed notes was built with a vision model, and the number has
        // to say so — a note recorded as zero vision pages would make the report understate itself.
        vision.blocks = List.of(read("Anything.", 0.9));

        assertThat(extractor().extract(png()).visionPages()).isEqualTo(1);
    }

    @Test
    void theExtractorClaimsImagesAndNothingElse() {
        HandwrittenNoteExtractor extractor = extractor();

        assertThat(extractor.supports(DocumentFormat.PNG)).isTrue();
        assertThat(extractor.supports(DocumentFormat.JPEG)).isTrue();
        assertThat(extractor.supports(DocumentFormat.PDF)).isFalse();
        assertThat(extractor.supports(DocumentFormat.DOCX)).isFalse();
    }

    private static TranscribedBlock read(String content, double confidence) {
        // Straight off the client, which does not judge: `indexed` is the extractor's call and is
        // false on everything arriving here.
        return new TranscribedBlock(0, content, confidence, false);
    }

    // ── the stub ────────────────────────────────────────────────────────────────────────────

    private static final class FakeVisionClient implements VisionClient {

        private boolean configured = true;
        private List<TranscribedBlock> blocks = List.of();
        private final List<String> mimeTypes = new ArrayList<>();
        private int calls = 0;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String readPage(byte[] pngImage, PageDefect hint) {
            throw new UnsupportedOperationException("A note is not a routed PDF page.");
        }

        @Override
        public List<TranscribedBlock> readHandwriting(byte[] image, String mimeType) {
            calls++;
            mimeTypes.add(mimeType);
            // Renumbered here rather than in the test data, so a test can list its blocks without
            // also having to count them.
            List<TranscribedBlock> numbered = new ArrayList<>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                TranscribedBlock block = blocks.get(i);
                numbered.add(new TranscribedBlock(i, block.content(), block.confidence(), false));
            }
            return numbered;
        }
    }
}
