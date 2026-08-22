package com.studyloop.backend.document;

// The uploaded file isn't something we can ingest → 415.
//
// Takes the finished sentence rather than a content type, because from Phase 16 the useful message
// depends on *why*: a `.ppt` needs "press Save As", a `.jpg` on the materials endpoint needs "that
// is a note, not material", and an unrecognised type needs the list of what is accepted. Building
// all three from one string inside here would put the decision in the wrong class — DocumentFormat
// knows what was allowed at that call site and this does not.
public class UnsupportedDocumentTypeException extends RuntimeException {

    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }
}
