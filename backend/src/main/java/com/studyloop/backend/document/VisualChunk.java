package com.studyloop.backend.document;

// A page about to be indexed as a picture (Phase 17.2), before it becomes a persisted row.
//
// **Two representations of one page, and neither is optional.** The `image` is what gets embedded,
// so it is what retrieval matches on — a question about "the diagram with the three layers" reaches
// this row through the picture and not through any word on it. The `content` is what gets put in
// the prompt, and it has to exist because Command R is text-only: a chunk retrieved by its picture
// and carrying no words could be found and then not used, which is the worst of both.
//
// The image does not survive this record. It goes into one embedding request and is dropped; what
// the database keeps is the vector and the text, exactly as for a text chunk. Storing page renders
// would multiply the size of a course's corpus by an order of magnitude to hold bytes nothing reads
// twice — the PDF they came from is already in storage, and re-rendering is cheap.
public record VisualChunk(
        // Continues the document's chunk index sequence after its text chunks, so the
        // (document, chunk_index) unique constraint keeps meaning what it meant.
        int index,
        // Both ends of the span are this page: a picture is on exactly one page.
        int pageNumber,
        String content,
        int tokenCount,
        byte[] image
) {
}
