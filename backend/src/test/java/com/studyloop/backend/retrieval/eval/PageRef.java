package com.studyloop.backend.retrieval.eval;

// One place in the corpus: a fixture document, and a 1-based page inside it.
//
// Retrieval is course-scoped, and the fixture course holds fourteen documents, so a page number on
// its own is ambiguous — page 4 exists in every chapter. Grading has to compare the pair, or a
// chunk from page 4 of the sorting chapter would be credited against an answer that lives on page 4
// of the hashing chapter.
public record PageRef(FixtureDocument document, int page) {

    public static PageRef of(FixtureDocument document, int page) {
        return new PageRef(document, page);
    }

    @Override
    public String toString() {
        return document.fileName() + ":" + page;
    }
}
