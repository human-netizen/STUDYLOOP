package com.studyloop.backend.document;

// Retire or un-retire asked of a document in a status where the verb means nothing → 409.
//
// Retire only applies to READY, because READY is the only status any reader can see: an UPLOADED
// or FAILED document is already out of every search, every quiz and every flashcard generation, so
// "retiring" it would be an action the UI offered and the database ignored. Un-retire only applies
// to RETIRED, for the mirror reason — it would otherwise be a way to mark a half-ingested document
// READY and put a document with no chunks into the corpus.
public class DocumentNotRetirableException extends RuntimeException {

    public DocumentNotRetirableException(DocumentStatus status) {
        super("A document in status " + status + " cannot be retired or un-retired.");
    }
}
