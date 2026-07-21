package com.studyloop.backend.document;

// The request carried no file part, or the file was empty → 400.
public class EmptyDocumentException extends RuntimeException {

    public EmptyDocumentException() {
        super("No file was uploaded, or the file is empty.");
    }
}
