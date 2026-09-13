package com.studyloop.backend.document;

import com.studyloop.backend.document.DocumentTaxonomyInferrer.InferredTaxonomy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Phase 23.2 — what a filename says about the material behind it.
//
// **The tests that matter most here are the negative ones.** A week or a category read wrongly is
// not a wrong badge: it is a question about week 3 quietly retrieving four documents that are not
// week 3, with a confident cited answer drawn from the wrong place and nothing anywhere saying so.
// The false-positive cases below — `syllabus`, `collaborative`, `institute` — are the ones a
// contains-check passes and a word-bounded match does not.
class DocumentTaxonomyInferrerTest {

    private final DocumentTaxonomyInferrer inferrer = new DocumentTaxonomyInferrer();

    @ParameterizedTest
    @CsvSource({
            "Week 3 - Hashing.pdf, 3",
            "week3-hashing.pdf, 3",
            "Week_03_Hashing.pptx, 3",
            "Week-12 Treaps.docx, 12",
            "wk 7 notes.pdf, 7",
            "WEEK 1.pdf, 1",
    })
    void readsTheWeekOffTheFilename(String filename, int expected) {
        assertEquals(expected, inferrer.infer(filename).week());
    }

    // A number that happens to follow some other word is not a week, and neither is one outside
    // the column's range — the check constraint would reject it, and a value rejected at the
    // database is a 500 on an upload that has already stored its bytes.
    @ParameterizedTest
    @ValueSource(strings = {
            "Lecture 3 - Hashing.pdf",
            "chapter 3.pdf",
            "week 0 intro.pdf",
            "week 99 far future.pdf",
            "scan_0012.pdf",
    })
    void doesNotInventAWeek(String filename) {
        assertNull(inferrer.infer(filename).week(),
                () -> filename + " does not name a week this course could have");
    }

    @ParameterizedTest
    @CsvSource({
            "Lecture 4 - Sorting.pptx, LECTURE",
            "week 2 slides.pdf, LECTURE",
            "Lab 3 - Linked lists.pdf, LAB",
            "practical-2.pdf, LAB",
            "Tutorial 5.pdf, TUTORIAL",
            "tut6 solutions.pdf, TUTORIAL",
            "Assignment 1 brief.docx, ASSIGNMENT",
            "hw2.pdf, ASSIGNMENT",
            "problem set 4.pdf, ASSIGNMENT",
            "Midterm 2024.pdf, EXAM",
            "past paper 2023.pdf, EXAM",
            "Chapter 6 - Binary trees.pdf, READING",
            "ch12.pdf, READING",
            "textbook extract.pdf, READING",
            "scan_0012.pdf, UNCLASSIFIED",
            "'', UNCLASSIFIED",
    })
    void readsTheCategoryOffTheFilename(String filename, DocumentCategory expected) {
        assertEquals(expected, inferrer.infer(filename).category());
    }

    // The whole reason every pattern is word-bounded. Each of these contains a category keyword as
    // a substring and is not that kind of material; a contains-check files all three wrongly, and
    // the first one — the course syllabus filed as a lab — is the one that would actually happen.
    @ParameterizedTest
    @ValueSource(strings = {
            "syllabus.pdf",
            "collaborative-filtering.pdf",
            "institute-handbook.pdf",
            "prelecture.pdf",
    })
    void aKeywordInsideAWordIsNotAKeyword(String filename) {
        assertEquals(DocumentCategory.UNCLASSIFIED, inferrer.infer(filename).category(),
                () -> filename + " contains a category word as a substring and is not that thing");
    }

    // Precedence, stated as a test because the rule list is ordered and an ordered list is the
    // kind of thing a later edit reorders without noticing.
    @Test
    void theMoreSpecificKeywordWins() {
        assertEquals(DocumentCategory.LAB,
                inferrer.infer("Lab 4 - assignment brief.pdf").category(),
                "a lab whose brief is the assignment is a lab");
        assertEquals(DocumentCategory.EXAM,
                inferrer.infer("Midterm revision lecture.pptx").category(),
                "a lecture about the midterm is filed with the exam material");
    }

    @Test
    void bothHalvesAreReadFromOneName() {
        InferredTaxonomy inferred = inferrer.infer("Week 09 - Lab 4 - Heaps.pdf");

        assertEquals(9, inferred.week());
        assertEquals(DocumentCategory.LAB, inferred.category());
    }

    @Test
    void aMissingFilenameIsNotAnError() {
        assertEquals(InferredTaxonomy.NOTHING, inferrer.infer(null));
        assertEquals(InferredTaxonomy.NOTHING, inferrer.infer("   "));
    }
}
