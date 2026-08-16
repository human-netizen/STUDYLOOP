package com.studyloop.backend.document;

import java.util.UUID;

// Published once an uploaded document is committed. A transaction-bound, async listener
// picks it up after commit and starts the ingestion pipeline — so the pipeline never races
// ahead of the row it depends on.
//
// uploadedBy travels with the event rather than being re-read from the document, because the
// listener runs on the ingestion executor with no request behind it: ingestion is the largest
// single block of embedding calls this app makes, and without an actor on the event the whole
// cost of a 60-page upload would land in the ledger under nobody.
public record DocumentUploadedEvent(UUID documentId, UUID uploadedBy) { }
