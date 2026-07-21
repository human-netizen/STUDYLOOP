package com.studyloop.backend.document;

// Lifecycle of an uploaded document as it moves through the ingestion pipeline. Phase 4.1
// only ever sets UPLOADED; the async pipeline (4.2) drives the rest, ending in READY or
// FAILED.
public enum DocumentStatus {
    UPLOADED,
    EXTRACTING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED
}
