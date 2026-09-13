package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.DocumentCategory;

import java.util.List;

// Phase 23.2 — what this course is actually organised by, as opposed to what it could be.
//
// **The vocabulary is read out of the corpus rather than declared.** The picker offers the weeks
// that have material in them and the tags somebody has used; a course that has never labelled
// anything gets an empty list and a UI that says so, instead of a dropdown of 52 empty weeks.
//
// It is also what makes the query-side filter honest: "week 3" is a filter in a course that has a
// week 3 and three words in a course that does not. The retrieval path does not read this DTO —
// it resolves the same question to document ids in one query — but the two agree by construction
// because both ask the corpus rather than a configuration.
public record CourseTaxonomy(
        // Ascending, distinct, nulls excluded. Empty when nothing carries a week.
        List<Integer> weeks,
        // Only the categories present on at least one document, UNCLASSIFIED included — a library
        // filter for "not labelled yet" is the one place that value is worth offering, which is
        // why `DocumentCategory.isFilterable()` guards the *query* extractor and not this list.
        List<DocumentCategory> categories,
        List<String> tags
) {
}
