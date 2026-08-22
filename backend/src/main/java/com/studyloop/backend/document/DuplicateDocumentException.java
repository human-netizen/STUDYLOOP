package com.studyloop.backend.document;

// These exact bytes are already in this course as somebody else's private note (Phase 16.3) → 409.
//
// A course holds one copy of any given file — `(course_space_id, sha256)` is a unique constraint,
// and re-uploading identical bytes normally returns the existing document rather than storing a
// second copy. That is right for material, where every document is the course's, and wrong for a
// note that belongs to one member: handing back their row would disclose that they uploaded it,
// what they named it, and when.
//
// Rare in practice — two photographs are never byte-identical — and reachable when a screenshot is
// passed between two people. Refusing is the honest outcome: there is nothing this endpoint can
// return that is both true and safe.
public class DuplicateDocumentException extends RuntimeException {

    public DuplicateDocumentException() {
        super("Another member has already uploaded this exact file as a private note. "
                + "Ask them to promote it to the course, or upload your own copy of the page.");
    }
}
