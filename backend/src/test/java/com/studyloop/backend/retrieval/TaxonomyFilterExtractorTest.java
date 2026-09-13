package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.DocumentCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Phase 23.2 — what a question says about where to look.
//
// **The bar for a positive match is higher here than in the filename inferrer, and the negative
// cases are why.** A filename is read once and shown to a person who can correct it. A question is
// read on every turn and the narrowing it produces is applied immediately — so a false positive
// here is an answer drawn from the wrong subset of the corpus, and the reader's only evidence is
// a line of text saying which subset. Hence no abbreviations, and no `final` or `chapter`.
class TaxonomyFilterExtractorTest {

    private final TaxonomyFilterExtractor extractor = new TaxonomyFilterExtractor();

    @ParameterizedTest
    @CsvSource({
            "'What did we cover in week 3?', 3",
            "'what was in week three', 3",
            "'Week 12 summary please', 12",
            "'remind me about wk 7', 7",
            "'explain the week of 5 material', 5",
    })
    void readsTheWeekOutOfTheQuestion(String question, int expected) {
        assertEquals(expected, extractor.extract(question).filter().week());
    }

    @ParameterizedTest
    @CsvSource({
            "'what did the lecture say about hashing', LECTURE",
            "'show me the slides on treaps', LECTURE",
            "'what was the lab about', LAB",
            "'which practicals covered sorting', LAB",
            "'the tutorial on recursion', TUTORIAL",
            "'what is in assignment 2', ASSIGNMENT",
            "'the homework about graphs', ASSIGNMENT",
            "'what came up in the midterm', EXAM",
            "'past papers on heaps', EXAM",
            "'the textbook section on tries', READING",
    })
    void readsTheKindOfMaterialOutOfTheQuestion(String question, DocumentCategory expected) {
        assertEquals(expected, extractor.extract(question).filter().category());
    }

    // The words this extractor deliberately does not know, and each one is a decision rather than
    // a gap. `final` and `chapter` are ordinary English in a question about an algorithm; `hw`,
    // `pset`, `lec` and `tut` are filename shorthand nobody types into a sentence. Matching any of
    // them would narrow a question that asked for nothing of the kind.
    @ParameterizedTest
    @ValueSource(strings = {
            "what is the final complexity of quicksort",
            "explain chapter 6 on binary trees",
            "what does hw stand for in this notation",
            "describe the institute of the algorithm",
            "how does a hash table work",
            "what is a treap",
    })
    void aQuestionThatNamedNothingNarrowsNothing(String question) {
        assertSame(TaxonomyFilter.NONE, extractor.extract(question).filter(),
                () -> "nothing in \"" + question + "\" asks for a narrowed search");
    }

    // Nobody asks about the documents their instructor has not labelled yet, so the one value the
    // extractor must never produce is the one that means "unlabelled" — it would narrow a search
    // to precisely the material the reader did not ask for.
    @Test
    void neverProducesUnclassified() {
        for (String question : new String[] {
                "what is unclassified material", "show me the uncategorised documents"}) {
            DocumentCategory category = extractor.extract(question).filter().category();
            assertTrue(category == null || category.isFilterable(),
                    () -> question + " must not resolve to UNCLASSIFIED");
        }
    }

    @Test
    void bothHalvesComeOutOfOneQuestion() {
        TaxonomyFilter filter = extractor.extract("what did the week 4 lab cover").filter();

        assertEquals(4, filter.week());
        assertEquals(DocumentCategory.LAB, filter.category());
        assertEquals("Week 4 · Lab", filter.describe());
    }

    // Out of the column's range is a number that happened to follow the word, not a week.
    @ParameterizedTest
    @ValueSource(strings = {"is week 0 the first one", "what about week 99"})
    void aWeekOutsideTheRangeIsNotAWeek(String question) {
        assertNull(extractor.extract(question).filter().week());
    }

    @Test
    void aBlankQuestionIsNotAnError() {
        assertSame(TaxonomyFilter.NONE, extractor.extract(null).filter());
        assertSame(TaxonomyFilter.NONE, extractor.extract("   ").filter());
    }

    // What the reader is shown. Empty rather than null when nothing was applied, so the client
    // renders no badge instead of an empty one.
    @Test
    void describesWhatWasApplied() {
        assertEquals("Week 3", new TaxonomyFilter(3, null).describe());
        assertEquals("Lab", new TaxonomyFilter(null, DocumentCategory.LAB).describe());
        assertEquals("", TaxonomyFilter.NONE.describe());
    }

    // **The routing phrase is spent once it has chosen the documents.** With `lexical-or` off -
    // the shipped default - `plainto_tsquery` AND-joins the question's content words, and no
    // chunk in any corpus contains the word "week": left in, the phrase empties the sparse half
    // of hybrid retrieval on precisely the questions this stage exists for.
    @ParameterizedTest
    @CsvSource({
            "'what did week 3 say about linear probing', 'what did say about linear probing'",
            "'what did the week 4 lab cover about heaps', 'what did the cover about heaps'",
            "'the lecture on treaps', 'the on treaps'",
    })
    void theRoutingPhraseIsTakenOutOfTheSearchQuery(String question, String expected) {
        assertEquals(expected, extractor.extract(question).searchQuery());
    }

    // A question that named nothing is returned character for character, which is what makes this
    // free for every caller and invisible to every published number.
    @Test
    void aQuestionThatNamedNothingIsNotRewritten() {
        String question = "how does linear probing handle a collision";

        assertEquals(question, extractor.extract(question).searchQuery());
    }

    // A question that was *only* a routing phrase has nothing left to search for, and searching
    // for nothing inside the right documents returns nothing at all. The original is the better
    // query there - the narrowing still applies either way.
    @Test
    void aQuestionThatWasOnlyARoutingPhraseKeepsItsWords() {
        assertEquals("week 3?", extractor.extract("week 3?").searchQuery());
    }
}
