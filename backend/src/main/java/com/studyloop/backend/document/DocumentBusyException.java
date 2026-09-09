package com.studyloop.backend.document;

// A verb asked of a document whose ingestion is still running → 409.
//
// Re-ingesting mid-ingest is the case that matters: DocumentChunkService.replaceChunks deletes a
// document's chunks before inserting the new ones, so two pipelines over one document would
// interleave a delete with the other run's inserts and leave a corpus that is half of each. The
// status is the lock, and it is the same status a poller is already watching.
public class DocumentBusyException extends RuntimeException {

    public DocumentBusyException(DocumentStatus status) {
        super("This document is still being ingested (" + status + "). Wait for it to finish.");
    }
}
