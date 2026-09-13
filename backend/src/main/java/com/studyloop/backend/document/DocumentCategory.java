package com.studyloop.backend.document;

// Phase 23.2 — what kind of material a document is.
//
// **Seven values, and the list is deliberately short.** Every one of them is a word a course
// actually uses for its own files, and the test for adding an eighth is whether a reader would
// ever choose it as a filter — not whether it names a thing that exists. A taxonomy nobody
// narrows by is a column that costs an update path and buys a badge.
//
// UNCLASSIFIED is a member rather than a null, which keeps `category` `not null` and keeps every
// filter one equality. It is also the honest value for the several hundred rows that existed
// before this enum did: they are not lectures, they are unlabelled.
public enum DocumentCategory {

    LECTURE,
    LAB,
    TUTORIAL,
    ASSIGNMENT,
    EXAM,
    READING,
    UNCLASSIFIED;

    // Whether this is a value a filter can mean. Nobody asks for "the unclassified ones" in a
    // question, so an extractor that produced UNCLASSIFIED would be narrowing a search to the
    // documents its author had not got round to labelling — which is the opposite of what the
    // reader asked for and would look like a retrieval bug.
    public boolean isFilterable() {
        return this != UNCLASSIFIED;
    }
}
