package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.Language;

import java.time.Instant;
import java.util.UUID;

// A document and its ingestion state. Its `id` doubles as the job handle the client polls
// via GET /courses/{courseId}/documents/{id} while the pipeline runs.
public record DocumentResponse(
        UUID id,
        UUID courseId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        DocumentStatus status,
        // Non-null only when status is FAILED — the reason ingestion stopped.
        String errorMessage,
        // Phase 25.2 — how far through the pipeline this document is, 0-100, and the sentence to
        // show under the bar. Sent on every document rather than only the moving ones, because the
        // client polls a list and a field that appears and disappears is a field every consumer has
        // to guard. A finished document reads 100; a failed one holds where it stopped.
        int progress,
        String stage,
        Integer pageCount,
        // Phase 25.1 — the slice of the source document that was ingested, or null for all of it.
        // The client renders "229 pages · 12–240" from these; nothing downstream computes with them,
        // because the page numbers on the chunks are already the source document's own.
        Integer firstPage,
        Integer lastPage,
        // Phase 25.3 — pages whose vision call timed out and kept the text PDFBox extracted. Zero
        // for almost every document. Sent so the row can say it out loud: the whole defence of
        // degrading instead of failing is that the degradation is visible.
        int degradedPages,
        // Phase 19.1. ENGLISH until extraction has run, and ENGLISH afterwards for everything the
        // detector did not find Bengali script in — so this is never null and never unknown.
        Language language,
        UUID uploadedById,
        Instant createdAt,
        Instant updatedAt
) {

    // courseId is passed in (from the request path) so this never touches the lazy course
    // association; uploadedBy is read only for its id, which a proxy answers without a load.
    public static DocumentResponse from(Document document, UUID courseId) {
        return new DocumentResponse(
                document.getId(),
                courseId,
                document.getFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getSha256(),
                document.getStatus(),
                document.getErrorMessage(),
                document.getProgress(),
                document.getStage(),
                document.getPageCount(),
                document.getFirstPage(),
                document.getLastPage(),
                document.getDegradedPages(),
                document.getLanguage(),
                document.getUploadedBy().getId(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
