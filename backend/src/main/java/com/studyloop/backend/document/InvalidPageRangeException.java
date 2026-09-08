package com.studyloop.backend.document;

// The requested page range cannot be read as a range at all → 400 (Phase 25.1).
//
// Only for what a client can get wrong without ever opening the file: a first page below one, or a
// last page before the first. **A range that runs past the end of the document is deliberately not
// one of these** — that is "to the end", and refusing it would turn a client whose page count came
// from a slightly different copy of the file into a failed upload.
public class InvalidPageRangeException extends RuntimeException {

    public InvalidPageRangeException(String message) {
        super(message);
    }
}
