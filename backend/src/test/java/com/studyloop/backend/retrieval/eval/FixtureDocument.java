package com.studyloop.backend.retrieval.eval;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

// The committed fixture corpus: the chapters of Pat Morin's *Open Data Structures* (Java edition),
// split one chapter per file so the eval runs against a course with fourteen lectures rather than
// one monolithic document.
//
// Why this book. It is CC BY 2.5 Canada, so it can live in a public repository and be redistributed
// with attribution — see fixtures/README.md. It is real textbook prose with headings, tables,
// figures, pseudocode and cross-references, which is what Phase 13's structural chunking will need
// to be measured against. And the chapters are deliberately confusable with one another:
// skiplists, treaps, scapegoat trees and red-black trees all answer "what is the expected search
// time", so retrieving the right one is a genuine discrimination problem rather than topic
// matching. A corpus of unrelated documents would make every phase look better than it is.
public enum FixtureDocument {

    INTRODUCTION("01-introduction.pdf", "Lecture 1 — Introduction"),
    ARRAY_BASED_LISTS("02-array-based-lists.pdf", "Lecture 2 — Array-Based Lists"),
    LINKED_LISTS("03-linked-lists.pdf", "Lecture 3 — Linked Lists"),
    SKIPLISTS("04-skiplists.pdf", "Lecture 4 — Skiplists"),
    HASH_TABLES("05-hash-tables.pdf", "Lecture 5 — Hash Tables"),
    BINARY_TREES("06-binary-trees.pdf", "Lecture 6 — Binary Trees"),
    RANDOM_BINARY_SEARCH_TREES("07-random-binary-search-trees.pdf", "Lecture 7 — Random Binary Search Trees"),
    SCAPEGOAT_TREES("08-scapegoat-trees.pdf", "Lecture 8 — Scapegoat Trees"),
    RED_BLACK_TREES("09-red-black-trees.pdf", "Lecture 9 — Red-Black Trees"),
    HEAPS("10-heaps.pdf", "Lecture 10 — Heaps"),
    SORTING_ALGORITHMS("11-sorting-algorithms.pdf", "Lecture 11 — Sorting Algorithms"),
    GRAPHS("12-graphs.pdf", "Lecture 12 — Graphs"),
    DATA_STRUCTURES_FOR_INTEGERS("13-data-structures-for-integers.pdf", "Lecture 13 — Data Structures for Integers"),
    EXTERNAL_MEMORY_SEARCHING("14-external-memory-searching.pdf", "Lecture 14 — External Memory Searching");

    private static final String CLASSPATH_DIR = "fixtures/";

    private final String fileName;
    private final String title;

    FixtureDocument(String fileName, String title) {
        this.fileName = fileName;
        this.title = title;
    }

    public String fileName() {
        return fileName;
    }

    // Display only — what a printed eval report calls this document, so the report reads like a
    // course rather than like a file listing. The seeded document's filename is fileName(), because
    // that is what a retrieved chunk carries and therefore what maps a hit back to a fixture.
    public String title() {
        return title;
    }

    // The fixture a retrieved chunk came from, or null if it came from something this corpus did
    // not seed. Null rather than a throw: the eval grades an unknown document as a miss, since it
    // still occupied a slot in the top-k, and a stray document is a seeding problem the corpus
    // report should surface rather than an exception mid-run.
    public static FixtureDocument byFileName(String fileName) {
        for (FixtureDocument document : values()) {
            if (document.fileName.equals(fileName)) {
                return document;
            }
        }
        return null;
    }

    public byte[] bytes() {
        try {
            return new ClassPathResource(CLASSPATH_DIR + fileName).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Fixture " + fileName + " is missing from src/test/resources/" + CLASSPATH_DIR, e);
        }
    }
}
