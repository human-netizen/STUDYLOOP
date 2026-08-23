package com.studyloop.backend.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 18.1. What the trigram list is allowed to search on, which is the decision that makes the
// difference between a useful fuzzy retriever and one that returns the whole course for every
// question.
class QueryTermsTest {

    @Test
    void keepsTheDistinctiveWordsAndDropsTheScaffolding() {
        assertThat(QueryTerms.of("What does a SkiplistSSet guarantee for find?", 8))
                .containsExactlyInAnyOrder("skiplistsset", "guarantee", "find");
    }

    @Test
    void keepsSubjectMatterAGeneralStopwordListWouldRemove() {
        // The reason the stopword list is hand-written rather than borrowed. In a data-structures
        // corpus these five words are the subject, and a general English list drops four of them.
        assertThat(QueryTerms.of("the list, the heap, the tree, the time and the order", 8))
                .containsExactlyInAnyOrder("list", "heap", "tree", "time", "order");
    }

    @Test
    void dropsWordsTooShortToMatchOnAndDeduplicates() {
        // "n" and "log" are under four letters: a two-trigram term is similar to half the corpus.
        // "sort" appears twice and is one term, because the same predicate twice is the same scan.
        assertThat(QueryTerms.of("is n log n the sort bound for sort", 8))
                .containsExactly("bound", "sort");
    }

    @Test
    void isEmptyForAQuestionMadeEntirelyOfScaffolding() {
        // Not an error: the caller reads it as one fewer list to fuse. There is nothing here a term
        // index could match, and a follow-up like this one is answered from the thread's history.
        assertThat(QueryTerms.of("what about that one?", 8)).isEmpty();
        assertThat(QueryTerms.of("   ", 8)).isEmpty();
        assertThat(QueryTerms.of(null, 8)).isEmpty();
    }

    @Test
    void keepsTheLongestTermsWhenThereAreMoreThanTheCap() {
        // Longest first, because each term costs an index scan and a similarity evaluation per
        // candidate, and the long ones are the ones a typo is worth correcting in.
        assertThat(QueryTerms.of("amortised analysis of the resize operation in an array stack", 3))
                .containsExactly("amortised", "operation", "analysis");
    }

    @Test
    void isStableSoTheSameQuestionBuildsTheSameSql() {
        // Terms of equal length keep the order they were typed in. The SQL is generated from this
        // list, so an unstable sort would make the same question two different prepared statements.
        assertThat(QueryTerms.of("heap tree list", 8)).containsExactly("heap", "tree", "list");
    }

    @Test
    void splitsOnPunctuationAndNotationSoAFormulaContributesNothing() {
        // "O(log n)" contributes nothing, and "red-black" contributes only "black" — the hyphen
        // splits it and "red" is under the length floor. A real cost of splitting on punctuation,
        // and an acceptable one: the term that survives is the distinctive half of the pair, and
        // the lexeme list still has the hyphenated form indexed whole.
        assertThat(QueryTerms.of("Is find(x) really O(log n) in a red-black tree?", 8))
                .containsExactlyInAnyOrder("really", "black", "find", "tree");
    }
}
