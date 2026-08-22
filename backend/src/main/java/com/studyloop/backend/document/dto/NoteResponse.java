package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.DocumentVisibility;

import java.time.Instant;
import java.util.UUID;

// A digitised handwritten note (Phase 16.3).
//
// Not DocumentResponse, though it is the same row underneath, because the two answer different
// questions. A material's list needs its size and hash; a note's list needs whether it is still
// private, whether it is yours, and whether it is finished being read — and `mine` is the one
// field that cannot be computed on the client, since the caller's own id is not in the payload.
public record NoteResponse(
        UUID id,
        UUID courseId,
        String filename,
        DocumentStatus status,
        // Non-null only when status is FAILED — usually "the photo was too blurred to read".
        String errorMessage,
        DocumentVisibility visibility,
        // Whether the caller uploaded this. The list mixes your own private notes with notes the
        // course has promoted, and those are different things to be looking at.
        boolean mine,
        UUID uploadedById,
        Instant createdAt,
        Instant updatedAt
) {

    public static NoteResponse from(Document note, UUID courseId, UUID actorId) {
        UUID uploader = note.getUploadedBy().getId();
        return new NoteResponse(
                note.getId(),
                courseId,
                note.getFilename(),
                note.getStatus(),
                note.getErrorMessage(),
                note.getVisibility(),
                uploader.equals(actorId),
                uploader,
                note.getCreatedAt(),
                note.getUpdatedAt());
    }
}
