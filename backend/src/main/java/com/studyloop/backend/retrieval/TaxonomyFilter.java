package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.DocumentCategory;

// Phase 23.2 — the narrowing a question asked for, before anything has checked whether the course
// can honour it.
//
// **Two fields and no tags, and that is a decision rather than an omission.** Week and category
// are closed vocabularies: there are fifty-two of one and seven of the other, both are written
// into this codebase, and a regex over them is either right or silent. Tags are open — they are
// whatever somebody typed — so matching them against a question means matching arbitrary words,
// and the failure is not a miss but a *false* narrowing: a course with a tag "trees" would have
// every question containing the word "trees" quietly restricted to four documents. Tags are how a
// person browses a library, which is a place they can see what was applied and undo it.
public record TaxonomyFilter(Integer week, DocumentCategory category) {

    public static final TaxonomyFilter NONE = new TaxonomyFilter(null, null);

    public boolean isEmpty() {
        return week == null && category == null;
    }

    // What to show the reader when this filter was applied. A stage that narrows a search
    // invisibly is the failure this project has named in four other places — a switched-off
    // lexical half, a fail-open reranker, a half-generated corpus — so the applied filter travels
    // back to the client as a sentence rather than living in a log.
    public String describe() {
        if (isEmpty()) {
            return "";
        }
        if (week == null) {
            return label(category);
        }
        if (category == null) {
            return "Week " + week;
        }
        return "Week " + week + " · " + label(category);
    }

    private static String label(DocumentCategory category) {
        String name = category.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
