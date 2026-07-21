package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentStatus;

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
        Integer pageCount,
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
                document.getPageCount(),
                document.getUploadedBy().getId(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
