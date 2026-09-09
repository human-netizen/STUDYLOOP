package com.studyloop.backend.flashcard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 29.1's Anki export.
//
// These assert bytes rather than behaviour on purpose. Everywhere else in this codebase the thing
// under test is ours on both sides, so a test can ask what it means; here the reader is Anki, and
// what a card looks like after it arrives is decided by whether this file is RFC 4180 or merely
// looks like it. The interesting cases are all punctuation a lecture answer contains anyway —
// which is why "it worked on the deck I tried" is not evidence.
class FlashcardCsvTest {

    private static Flashcard card(String front, String back) {
        Flashcard card = new Flashcard();
        card.setFront(front);
        card.setBack(back);
        return card;
    }

    @Test
    void theFileSaysHowItShouldBeRead() {
        String csv = FlashcardCsv.of(List.of(card("Big-O of quicksort?", "O(n log n) average")));

        assertThat(csv).startsWith("#separator:Comma\r\n#html:false\r\n#columns:Front,Back\r\n");
    }

    @Test
    void anOrdinaryCardIsNotQuoted() {
        String csv = FlashcardCsv.of(List.of(card("What is a heap?", "A complete binary tree")));

        assertThat(csv).endsWith("What is a heap?,A complete binary tree\r\n");
    }

    @Test
    void aCommaDoesNotBecomeAThirdColumn() {
        String csv = FlashcardCsv.of(List.of(card("Name the phases", "Fetch, decode, execute")));

        assertThat(csv).endsWith("Name the phases,\"Fetch, decode, execute\"\r\n");
    }

    @Test
    void aQuoteInsideAFieldIsDoubled() {
        String csv = FlashcardCsv.of(List.of(card("Define \"pivot\"", "The element partitioned around")));

        assertThat(csv).endsWith("\"Define \"\"pivot\"\"\",The element partitioned around\r\n");
    }

    // The one that matters most for this product: a generated back is very often a list, and a
    // list is newlines. Unquoted, every card after the first would be shifted by one row.
    @Test
    void aMultiLineAnswerStaysOneRecord() {
        String csv = FlashcardCsv.of(List.of(card("Steps?", "- Partition\n- Recurse")));

        assertThat(csv).endsWith("Steps?,\"- Partition\n- Recurse\"\r\n");
    }

    @Test
    void surroundingSpaceIsPreserved() {
        String csv = FlashcardCsv.of(List.of(card(" indented", "back ")));

        assertThat(csv).endsWith("\" indented\",\"back \"\r\n");
    }

    @Test
    void everyCardIsItsOwnRecord() {
        String csv = FlashcardCsv.of(List.of(card("a", "1"), card("b", "2"), card("c", "3")));

        assertThat(csv.lines()).containsExactly(
                "#separator:Comma", "#html:false", "#columns:Front,Back", "a,1", "b,2", "c,3");
    }

    // An empty deck is a file with no cards in it, not an error. The button that produces it is
    // hidden until there is something to export; the endpoint does not need a second opinion.
    @Test
    void anEmptyDeckIsStillAValidFile() {
        String csv = FlashcardCsv.of(List.of());

        assertThat(csv).isEqualTo("#separator:Comma\r\n#html:false\r\n#columns:Front,Back\r\n");
    }
}
