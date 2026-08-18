package com.studyloop.backend.document;

import com.studyloop.backend.config.ChunkingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Adaptive chunking (Phase 13): the counter never decides a boundary, it only bounds one.
//
// **What this replaces.** A 400-word window slid across the document, stepping 340 words at a time,
// with a 60-word overlap to soften the damage. Every boundary in the corpus was placed by a
// counter running out, which meant most of them fell mid-sentence and some fell mid-word; the
// overlap existed so that an idea cut in half by one boundary survived whole in the neighbour that
// duplicated it. That is a workaround for the mechanism, and it cost real accuracy — the eval
// harness carries `PAGE_TOLERANCE = 1` for exactly the same reason.
//
// **The ladder.** Three tiers, tried in order, and the counter is not one of them:
//
//   1. structural — the headings the author wrote (`StructuralSplitter`). Free, and the normal case.
//   2. semantic  — where adjacent sentences stop being about the same thing (`SemanticSplitter`).
//      Only for documents with no headings at all. Costs one embedding pass.
//   3. recursive — paragraph, then sentence (`RecursiveSplitter`). Only for a block that came out
//      of tier 1 or 2 above the ceiling.
//
// **500 tokens is a ceiling, not a target.** A section that fits becomes one chunk at whatever size
// it naturally is — 80 tokens or 460. Nothing is padded to reach a number and nothing is sliced to
// reach one. The ceiling exists so a 4,000-token chapter cannot become a single vector that is the
// average of eight subtopics and a strong match for none of them.
//
// **No overlap.** A boundary-respecting splitter does not cut ideas in half, so there is nothing
// for duplicated text to rescue. What replaces it is the context header on `embedText`, which makes
// a chunk self-contained deterministically instead of by copying its neighbour's words into the
// index.
@Component
@RequiredArgsConstructor
public class TextChunker {

    private final ChunkingProperties properties;
    private final StructuralSplitter structuralSplitter;
    private final SemanticSplitter semanticSplitter;
    private final RecursiveSplitter recursiveSplitter;
    private final TokenCounter tokenCounter;

    // What a stored chunk is indexed as: its embed_text, or its content when there is none. One
    // place, because the vector column and the tsvector column have to agree on the answer — the
    // SQL says `coalesce(embed_text, content)` and this is the Java saying the same thing.
    public static String indexedText(DocumentChunk chunk) {
        return chunk.getEmbedText() != null ? chunk.getEmbedText() : chunk.getContent();
    }

    public List<TextChunk> chunk(List<PageText> pages) {
        return chunk(pages, null);
    }

    // `title` is the document's own name, and it is half of the context header. It is passed in
    // rather than read off the Document row because the chunker is also used for text that has no
    // document row yet (an accepted forum answer) and because a component that took a repository to
    // fetch one string would be harder to test than the thing it tests.
    public List<TextChunk> chunk(List<PageText> pages, String title) {
        List<SectionBlock> blocks = structuralSplitter.split(pages);
        if (blocks.isEmpty()) {
            throw new DocumentExtractionException(
                    "No extractable text was found. If this is a scanned PDF, it needs OCR first.");
        }
        if (!structuralSplitter.isStructured(blocks)) {
            blocks = semantic(blocks);
        }
        return toChunks(capped(merged(blocks)), title);
    }

    // Tier 2 sees the document as one block, because "no headings" is precisely the case where
    // there is nothing to divide it by yet.
    private List<SectionBlock> semantic(List<SectionBlock> blocks) {
        List<SectionBlock> split = new ArrayList<>();
        for (SectionBlock block : blocks) {
            split.addAll(semanticSplitter.split(block));
        }
        return split;
    }

    // Tiny sections absorb the sibling after them. "4.2.1 Analysis" holding one sentence is not a
    // retrieval unit — it is a fragment whose vector is dominated by whichever three words it
    // happens to contain — and merging it with what follows costs nothing, because what follows is
    // the rest of the same discussion.
    //
    // Two limits, and both are the point rather than safety rails. It never merges past the
    // ceiling, or the merge would undo what the ceiling is for. It never merges across an H1: two
    // chapters are adjacent in the file and nowhere else, and a chunk spanning the end of one and
    // the start of the next answers questions about neither.
    private List<SectionBlock> merged(List<SectionBlock> blocks) {
        List<SectionBlock> result = new ArrayList<>();
        SectionBlock pending = null;

        for (SectionBlock block : blocks) {
            if (pending == null) {
                pending = block;
                continue;
            }
            if (tokenCounter.count(pending.text()) < properties.minTokens()
                    && pending.topLevel().equals(block.topLevel())
                    && tokenCounter.count(pending.text() + "\n\n" + block.text()) <= properties.maxTokens()) {
                pending = pending.mergedWith(block);
                continue;
            }
            result.add(pending);
            pending = block;
        }
        if (pending != null) {
            result.add(pending);
        }
        return result;
    }

    private List<SectionBlock> capped(List<SectionBlock> blocks) {
        List<SectionBlock> result = new ArrayList<>();
        for (SectionBlock block : blocks) {
            result.addAll(recursiveSplitter.split(block, properties.maxTokens()));
        }
        return result;
    }

    private List<TextChunk> toChunks(List<SectionBlock> blocks, String title) {
        List<TextChunk> chunks = new ArrayList<>(blocks.size());
        int index = 0;
        for (SectionBlock block : blocks) {
            if (block.isEmpty()) {
                continue;
            }
            String content = block.text();
            String path = block.pathLabel().isEmpty() ? null : block.pathLabel();
            // Counted on `content`, not on the indexed text: this is the size of the passage, and
            // it is the number the ceiling above was applied to. Counting the context header here
            // would make a 480-token section report 495 against a ceiling of 500 and read as a bug
            // to anyone who looked at the column.
            chunks.add(new TextChunk(index++, block.pageStart(), block.pageEnd(), path,
                    content, embedTextFor(title, path, content), tokenCounter.count(content)));
        }
        if (chunks.isEmpty()) {
            throw new DocumentExtractionException(
                    "No extractable text was found. If this is a scanned PDF, it needs OCR first.");
        }
        return chunks;
    }

    // The context header (13.4): the document's name and the heading trail, prepended to the text
    // that gets embedded and lexically indexed.
    //
    // What it fixes: a chunk reading "The expected search time is O(log n)" is a perfectly good
    // passage and a hopeless index entry, because the thing it is about is written three headings
    // above it and every chapter in the book contains a sentence like it. The header puts
    // "Skiplists" back into the text a retriever sees, and it costs nothing — the heading path was
    // already parsed and the title is already on the row.
    //
    // **Not the document summary**, which is what the plan proposed. Two reasons, in order. It does
    // not exist yet: summaries are generated after ingestion reaches READY, from the chunks, so
    // there is nothing to prepend at the moment the chunks are written, and a second pass would
    // mean re-embedding every chunk in the document. And it would not discriminate: the same
    // summary on every chunk of a document shifts all of them by the same amount, so it cannot
    // change which chunk of that document wins — it can only float or sink the document as a whole,
    // which is the wrong effect and the wrong sign. This is written up in DEVIATIONS.md.
    private String embedTextFor(String title, String path, String content) {
        if (!properties.contextHeader()) {
            return null;
        }
        StringBuilder header = new StringBuilder();
        if (title != null && !title.isBlank()) {
            header.append(title.strip()).append('\n');
        }
        if (path != null) {
            header.append(path).append('\n');
        }
        // Nothing to add means nothing to store: a null embed_text tells the SQL to read `content`,
        // which is the same text without a copy of it in a second column.
        return header.isEmpty() ? null : header + content;
    }
}
