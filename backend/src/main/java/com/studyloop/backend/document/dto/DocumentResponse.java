package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentCategory;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.Language;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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
        // Phase 29.2 — the course document this one most resembles, and the share of its sampled
        // passages that were already there. Both null for almost everything.
        //
        // The id without the filename, deliberately: every caller of this DTO is rendering a list
        // of the same course's documents and already holds the name, so resolving it here would be
        // a join per row to send back a string the client has in hand.
        UUID nearDuplicateOfId,
        Double nearDuplicateScore,
        // Phase 23.2 — how this material is filed. `week` is null when nobody has said; `category`
        // is never null and reads UNCLASSIFIED when nobody has; `tags` is an empty list, never
        // null, for the reason `progress` is sent on every row — a field the client has to
        // null-check before it can map over it is a field every consumer guards separately.
        Integer week,
        DocumentCategory category,
        List<String> tags,
        UUID uploadedById,
        Instant createdAt,
        Instant updatedAt
) {

    // The tagless form, for the paths where a document is being reported rather than browsed —
    // an upload that has just landed, a re-ingest that has just been accepted. It sends an empty
    // tag list rather than reading the table, which is true for a new document and stale for
    // nothing a client renders from these responses: the library re-reads the list.
    public static DocumentResponse from(Document document, UUID courseId) {
        return from(document, courseId, Set.of());
    }

    // courseId is passed in (from the request path) so this never touches the lazy course
    // association; uploadedBy is read only for its id, which a proxy answers without a load.
    //
    // Tags are passed in rather than fetched, because every caller that has them has them for a
    // whole page at once — `DocumentTagRepository.tagsOf(List)` is one query for a library — and a
    // DTO that fetched its own would be the N+1 that query exists to avoid.
    public static DocumentResponse from(Document document, UUID courseId, Set<String> tags) {
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
                document.getNearDuplicateOf(),
                document.getNearDuplicateScore(),
                document.getWeekNumber(),
                document.getCategory(),
                tags == null ? List.of() : List.copyOf(tags),
                document.getUploadedBy().getId(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
