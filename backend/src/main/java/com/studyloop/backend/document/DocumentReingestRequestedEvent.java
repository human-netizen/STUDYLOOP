package com.studyloop.backend.document;

import java.util.UUID;

// A document whose stored bytes are to be run through the pipeline again (Phase 27.2).
//
// A separate event from DocumentUploadedEvent even though the listener does the same thing with
// both, because the two are not the same fact: one says a file arrived, the other says somebody
// asked for the file already here to be read differently. Reusing the upload event would have
// made every future listener of "a document was uploaded" — the forum sweep is one — fire on a
// re-cut of a document the course has had for a month.
public record DocumentReingestRequestedEvent(UUID documentId, UUID requestedBy) { }
