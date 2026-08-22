package com.studyloop.backend.document;

import java.util.UUID;

// A note asked to be promoted or exported before it finished being read → 409.
//
// Ingestion is asynchronous, so a note is uploaded, transcribed, chunked and embedded over several
// seconds while its row already exists. Promoting one mid-pipeline would publish a document with
// no chunks — retrievable by nobody, cited by nothing, and indistinguishable from a promotion that
// worked. Exporting one would hand over an empty .tex file.
public class NoteNotReadyException extends RuntimeException {

    public NoteNotReadyException(UUID noteId) {
        super("Note " + noteId + " has not finished being read yet. Wait for it to reach READY.");
    }
}
