package com.studyloop.backend.chat.dto;

import com.studyloop.backend.document.DocumentSource;
import com.studyloop.backend.retrieval.RetrievedChunk;

import java.util.UUID;

// One numbered source the answer was grounded on. `index` is the [n] marker the model uses
// inline, so the frontend can map a marker to its document + page. `snippet` is a short preview
// of the chunk text; the full jump-to-source viewer comes in Phase 6.2.
public record Citation(
        int index,
        UUID chunkId,
        UUID documentId,
        String filename,
        // UPLOAD sources open in the PDF viewer; a FORUM source has no file, so the client shows
        // it as what it is — an answer the class wrote — rather than offering a dead link.
        DocumentSource documentSource,
        Integer pageNumber,
        String snippet
) {

    private static final int SNIPPET_LENGTH = 240;

    public static Citation from(int index, RetrievedChunk chunk) {
        return new Citation(
                index,
                chunk.chunkId(),
                chunk.documentId(),
                chunk.filename(),
                chunk.source(),
                chunk.pageNumber(),
                snippet(chunk.content()));
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        if (trimmed.length() <= SNIPPET_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_LENGTH).stripTrailing() + "…";
    }
}
