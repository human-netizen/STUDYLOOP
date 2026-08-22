package com.studyloop.backend.document;

import com.studyloop.backend.config.VisionProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Turns a photograph of somebody's handwritten notes into a document the rest of StudyLoop already
// knows how to use (Phase 16.3).
//
// **Closing the loop is the entire point.** Digitising handwriting is a solved and widely-shipped
// trick — ZenLearn does it and stops at a LaTeX file, and I could find no path from that file back
// into its own retrieval corpus. Everything interesting is on the other side of that gap: once the
// note is a `Document` it goes through the same status machine, the same chunker, the same
// embedder and the same citation format, so it is immediately chattable, quizzable,
// flashcard-able and citable with **no new retrieval code at all**. This class is the converter
// that gets it there, and it is short precisely because the fifteen phases before it did the work.
//
// **The confidence threshold is where the honesty is.** A vision model reading print either sees a
// character or does not; reading handwriting it *infers*, and it is wrong routinely rather than
// rarely. A block the model half-guessed is not a slightly worse passage — indexed, it becomes a
// sentence the assistant will cite back at the student as their own note, and a plausible wrong
// recurrence relation is indistinguishable from a right one until somebody revises from it. So
// blocks below the threshold are kept, shown, and *not indexed*; a gap the student can see beats
// a guess they cannot.
@Component
@RequiredArgsConstructor
public class HandwrittenNoteExtractor implements DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(HandwrittenNoteExtractor.class);

    // The two container signatures worth checking. The stored content type came from the upload and
    // could be wrong in either direction — a phone that labels everything `image/jpeg`, a client
    // that labels nothing — and Gemini rejects an inline part whose declared type disagrees with
    // its bytes. The bytes are the only thing that cannot be mistaken about what they are.
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final VisionClient visionClient;
    private final VisionProperties properties;

    @Override
    public boolean supports(DocumentFormat format) {
        return format.isImage();
    }

    @Override
    public Extraction extract(byte[] bytes) {
        // Unlike the PDF router, an unconfigured key is fatal here rather than a warning. There is
        // no fallback reading of a photograph — the vision model is not improving an extraction,
        // it *is* the extraction — so continuing would store an empty document that reports READY.
        if (!visionClient.isConfigured()) {
            throw new VisionExtractionException(
                    "Reading handwritten notes needs a vision model, and no vision key is "
                            + "configured on this server.");
        }
        List<TranscribedBlock> read = visionClient.readHandwriting(bytes, mimeTypeOf(bytes));
        List<TranscribedBlock> judged = judge(read);

        String markdown = indexedMarkdown(judged);
        if (markdown.isBlank()) {
            throw new VisionExtractionException(
                    ("Nothing on this page could be read confidently enough to index. All %d "
                            + "blocks scored below %.2f — a sharper, straighter photo in better "
                            + "light usually fixes it.")
                            .formatted(judged.size(), properties.minNoteConfidence()));
        }
        int dropped = (int) judged.stream().filter(block -> !block.indexed()).count();
        if (dropped > 0) {
            log.info("Handwriting: indexed {} of {} blocks, {} below the {} confidence threshold",
                    judged.size() - dropped, judged.size(), dropped, properties.minNoteConfidence());
        }
        // One image is one page, and counted as one vision page for the same reason a routed PDF
        // page is: `documents.vision_pages` is what the eval report reads to say how the corpus was
        // built, and a corpus containing photographed notes was built with a vision model.
        return new Extraction(List.of(new PageText(1, markdown)), 1, judged);
    }

    private List<TranscribedBlock> judge(List<TranscribedBlock> read) {
        double threshold = properties.minNoteConfidence();
        List<TranscribedBlock> judged = new ArrayList<>(read.size());
        for (TranscribedBlock block : read) {
            judged.add(new TranscribedBlock(block.ordinal(), block.content(), block.confidence(),
                    block.confidence() >= threshold));
        }
        return judged;
    }

    // The kept blocks, joined by blank lines — which is what makes this a document rather than a
    // transcript. `StructuralSplitter` splits on blank lines and cuts on `#` headings, so a note
    // whose author wrote "Quicksort" across the top of the page arrives with that as its section
    // path, exactly as a textbook chapter does.
    //
    // A dropped block leaves no marker in the text. It is tempting to write "[3 lines could not be
    // read]" into the page so the gap is visible in an answer, and it is wrong: that sentence would
    // be embedded, lexically indexed and eventually retrieved and cited as if it were course
    // material. The gap belongs in the review view, which reads the blocks table.
    private static String indexedMarkdown(List<TranscribedBlock> blocks) {
        return blocks.stream()
                .filter(TranscribedBlock::indexed)
                .map(TranscribedBlock::content)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    private static String mimeTypeOf(byte[] bytes) {
        if (startsWith(bytes, PNG_MAGIC)) {
            return DocumentFormat.PNG.contentType();
        }
        if (startsWith(bytes, JPEG_MAGIC)) {
            return DocumentFormat.JPEG.contentType();
        }
        throw new DocumentExtractionException(
                "This file is not a PNG or JPEG image, whatever it is named.");
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
