package com.studyloop.backend.document;

import java.util.UUID;

// Published once an uploaded document is committed. A transaction-bound, async listener
// picks it up after commit and starts the ingestion pipeline — so the pipeline never races
// ahead of the row it depends on.
public record DocumentUploadedEvent(UUID documentId) { }
