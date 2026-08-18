package com.studyloop.backend.document;

// A prepared chunk before it becomes a persisted DocumentChunk.
//
// Two things here are new in Phase 13 and both are load-bearing:
//
//   - **A page span, not a page.** A chunk used to record where it started, and everything reading
//     it had to allow for the possibility that its content ran on to the next page — the eval's
//     `PAGE_TOLERANCE = 1` was that allowance written down. A section knows both of its ends.
//   - **`embedText` beside `content`.** `content` is what is displayed and cited; `embedText` is
//     what is embedded and lexically indexed, and it carries the context header (13.4). Keeping
//     them apart is what lets a chunk be self-contained to a retriever without the heading text
//     appearing in the middle of the passage a student reads.
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
        int tokenCount
) {

    // The same chunk with no page information, for text that was never in a document — an accepted
    // forum answer. Citing "p.1" of a file that does not exist invites a click through to nothing.
    public TextChunk withoutPages() {
        return new TextChunk(index, null, null, sectionPath, content, embedText, tokenCount);
    }
}
