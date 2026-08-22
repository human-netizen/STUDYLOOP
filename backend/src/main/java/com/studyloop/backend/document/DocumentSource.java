package com.studyloop.backend.document;

// Where a document's text came from. UPLOAD is a file a manager uploaded; FORUM is text this
// system wrote itself, from a forum answer an instructor accepted (Phase 9.2).
//
// Retrieval deliberately does not distinguish the two — both are the course's corpus, and an
// answer the class worked out is as citable as a lecture slide. The distinction matters to the
// UI (a FORUM document has no file to open) and to features that mean "the material you
// uploaded": the documents list and the default quiz source.
public enum DocumentSource {
    UPLOAD,
    FORUM,
    // A photograph of somebody's handwritten notes, read by a vision model (Phase 16.3). Unlike
    // the other two it is a *member's* document rather than the course's: it starts visible only
    // to the person who uploaded it and becomes course material only when a manager promotes it.
    // Retrieval honours that through documents.visibility, not through this column — the source
    // says where the text came from, the visibility says who may be answered from it.
    HANDWRITTEN
}
