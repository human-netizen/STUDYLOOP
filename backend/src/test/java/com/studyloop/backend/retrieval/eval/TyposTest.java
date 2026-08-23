package com.studyloop.backend.retrieval.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 18.4. The perturbation the trigram measurement rests on, so its rules are pinned: change
// one word, change it the same way every time, and change it inside the word.
class TyposTest {

    @Test
    void misspellsTheLongestWordAndLeavesTheRestAlone() {
        assertThat(Typos.inject("How many comparisons does merge-sort perform?"))
                .isEqualTo("How many compraisons does merge-sort perform?");
    }

    // Punctuation stays where it is: the word is measured on its letters, and a trailing question
    // mark is not part of what a student mistypes.
    @Test
    void keepsPunctuationInPlace() {
        assertThat(Typos.inject("At what occupancy does it shrink?"))
                .isEqualTo("At what occpuancy does it shrink?");
    }

    // The two swapped characters are always letters. Moving a letter across the hyphen would turn
    // "red-black" into "redb-lack", which is two different words rather than one misspelled one —
    // a different perturbation, with a different effect on both retrievers.
    @Test
    void neverSwapsALetterAcrossAHyphen() {
        assertThat(Typos.inject("a red-black tree")).isEqualTo("a red-lback tree");
    }

    // The midpoint of "running" falls between its two n's, and swapping those gives "running"
    // back — a question that was supposed to be misspelled and is not. Nothing downstream could
    // notice: an unmisspelled question simply retrieves well, and the run would report the stage
    // as having done less than it did.
    @Test
    void doesNotProduceTheWordBackWhenTheMidpointLettersMatch() {
        assertThat(Typos.inject("the running time")).isEqualTo("the rnuning time");
    }

    // The harness compares two runs. If the perturbation moved between them, it would be comparing
    // two different question sets and calling the difference a retrieval result.
    @Test
    void isDeterministic() {
        String question = "What is the expected height of a skiplist containing n elements?";
        assertThat(Typos.inject(question)).isEqualTo(Typos.inject(question));
    }

    // A question with no word long enough to misspell is left alone rather than mangled. Honest:
    // it is a question this perturbation has nothing to say about.
    @Test
    void leavesAQuestionOfShortWordsUntouched() {
        assertThat(Typos.inject("what is a set")).isEqualTo("what is a set");
    }
}
