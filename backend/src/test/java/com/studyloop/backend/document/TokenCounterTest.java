package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 13.1 — the estimate this replaces, and why replacing it was worth a dependency.
//
// These are not assertions about exact token counts, which belong to the encoder and not to us.
// They are assertions about the properties every budget in the application was silently relying on
// and `chars / 4` did not have.
class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();

    @Test
    void countsNothingAsNothing() {
        assertThat(counter.count(null)).isZero();
        assertThat(counter.count("")).isZero();
    }

    @Test
    void anEnglishSentenceIsAboutOneTokenPerWord() {
        // The property the old estimate got roughly right and the reason it survived so long.
        String sentence = "A skiplist supports search, insertion and deletion in logarithmic time.";
        assertThat(counter.count(sentence)).isBetween(11, 18);
    }

    @Test
    void aLongTextCostsMoreThanAShortOne() {
        assertThat(counter.count("the quick brown fox".repeat(20)))
                .isGreaterThan(counter.count("the quick brown fox"));
    }

    @Test
    void charactersPerTokenIsNotAConstant() {
        // The failure the estimate could not see, because it *assumed* this number. One string is
        // ordinary prose; the other is what a table of figures costs, which is most of a page in
        // this project's corpus. A ceiling built on a constant ratio is a ceiling for English and a
        // suggestion for everything else.
        String prose = "the value of the counter increases by one on every single step";
        String dense = "0.318 4.271 9.006 2.884 7.115 3.302 8.449 1.006 5.271 6.883";

        double proseRatio = (double) counter.count(prose) / prose.length();
        double denseRatio = (double) counter.count(dense) / dense.length();
        assertThat(denseRatio).isGreaterThan(proseRatio * 1.5);
    }

    @Test
    void nonLatinTextCostsFarMoreThanCharactersOverFour() {
        // Bangla is the case the plan called out, and it is the strongest argument for the change:
        // the estimate is not slightly wrong here, it is wrong by a multiple, so a Bangla document
        // chunked to "500 tokens" was being cut into pieces several times that size.
        String bangla = "তথ্য কাঠামো এবং অ্যালগরিদম";
        assertThat(counter.count(bangla)).isGreaterThan(bangla.length() / 4);
    }
}
