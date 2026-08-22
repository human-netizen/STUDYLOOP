package com.studyloop.backend.document;

// A prepared chunk before it becomes a persisted DocumentChunk.
//
// Three things here are not obvious and all are load-bearing:
//
//   - **A page span, not a page.** A chunk used to record where it started, and everything reading
//     it had to allow for the possibility that its content ran on to the next page — the eval's
//     `PAGE_TOLERANCE = 1` was that allowance written down. A section knows both of its ends.
//   - **`embedText` beside `content`.** `content` is what is displayed and cited; `embedText` is
//     what is embedded and lexically indexed, and it carries the context header (13.4) and the
//     synthetic queries (14.2). Keeping them apart is what lets a chunk be self-contained to a
//     retriever without any of that appearing in the passage a student reads.
//   - **`overflow`.** True when this chunk is one piece of a section the ceiling had to cut up
//     (tier 3), false when it is a section the document itself delimited. Phase 14 needs the
//     distinction: asking a model what questions a mid-section fragment answers produces questions
//     about whichever paragraph the counter happened to stop after.
public record TextChunk(
        int index,
        Integer pageStart,
        Integer pageEnd,
        // "Chapter 4 > 4.2 Skiplists", or null when the document stated no structure.
        String sectionPath,
        String content,
        // Null when it would be identical to `content`, which is both a storage saving and the
        // signal the SQL uses: `coalesce(embed_text, content)`.
        String embedText,
        int tokenCount,
        boolean overflow
) {

    // What this chunk is indexed as, with the same coalesce rule the SQL uses. The Java side of
    // `coalesce(embed_text, content)` for a chunk that has not been persisted yet.
    public String indexedText() {
        return embedText != null ? embedText : content;
    }

    // The same chunk with no page information, for text that was never in a document — an accepted
    // forum answer. Citing "p.1" of a file that does not exist invites a click through to nothing.
    public TextChunk withoutPages() {
        return new TextChunk(index, null, null, sectionPath, content, embedText, tokenCount, overflow);
    }

    // The same chunk with `block` appended to what gets indexed (Phase 14.2). It lands on
    // `embedText` and never on `content`, which is the whole safety property: the synthetic queries
    // are model-invented text, and the prompt is built from `content`.
    public TextChunk withIndexedSuffix(String block) {
        return new TextChunk(index, pageStart, pageEnd, sectionPath, content,
                indexedText() + "\n\n" + block, tokenCount, overflow);
    }
}
