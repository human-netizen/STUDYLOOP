package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 19.3 — sentence boundaries in a language with no full stop and no capital letters.
//
// **The defect this repairs was total rather than partial, which is why it is worth its own test
// class.** The boundary rule was "a full stop, then whitespace, then something that starts a
// sentence — a capital letter, a bracket or a quotation mark". Bangla ends a sentence with the
// danda `।` and has no letter case at all, so both halves failed independently and a Bangla
// document of any length was exactly one sentence. Two things followed from that, neither of which
// looked like a bug anywhere: tier 2 requires six sentences before it will measure meaning, so
// semantic splitting never ran on Bangla; and tier 3's sentence rung was a no-op, so an oversized
// Bangla block fell straight through to a hard word cut — the single failure the adaptive chunker
// was built to remove.
//
// `Sentences` is package-private, which is why this test lives here rather than beside the rest of
// Phase 19's.
class BanglaChunkingTest {

    // Twelve ordinary sentences of Bangla data-structures prose. Deliberately long enough to be
    // over the chunker's token ceiling, which is the condition under which tier 3 runs at all.
    private static final String LONG_BANGLA_PARAGRAPH = String.join(" ",
            "একটি অ্যারে হলো একই ধরনের উপাদানের ধারাবাহিক সংগ্রহ।",
            "সূচক ব্যবহার করে যেকোনো উপাদানে ধ্রুবক সময়ে প্রবেশ করা যায়।",
            "তবে মাঝখানে নতুন উপাদান যোগ করতে হলে পরের সব উপাদান এক ঘর সরাতে হয়।",
            "লিংকড লিস্টে প্রতিটি নোড পরবর্তী নোডের ঠিকানা ধরে রাখে।",
            "তালিকার শুরুতে সংযোজন এবং অপসারণ ধ্রুবক সময়ে সম্পন্ন হয়।",
            "কিন্তু সূচক ধরে কোনো উপাদানে পৌঁছাতে হলে শুরু থেকে হেঁটে যেতে হয়।",
            "স্ট্যাক একটি শেষে ঢুকে প্রথমে বের হওয়া কাঠামো।",
            "কিউ একটি প্রথমে ঢুকে প্রথমে বের হওয়া কাঠামো।",
            "বাইনারি সার্চ ট্রিতে প্রতিটি নোডের বাম দিকে ছোট মান থাকে।",
            "হ্যাশ টেবিল একটি হ্যাশ ফাংশন ব্যবহার করে চাবিকে ঘরের সূচকে রূপান্তর করে।",
            "স্কিপ লিস্ট একটি সম্ভাবনা নির্ভর কাঠামো।",
            "মার্জ সর্ট তালিকাটিকে বারবার দুই ভাগ করে এবং সাজানো অংশগুলো মিলিয়ে দেয়।");

    private final TokenCounter tokenCounter = new TokenCounter();

    @Test
    void banglaProseHasNothingTheOldRuleCouldHaveMatched() {
        // The premise, asserted rather than asserted-about. Every reason the English rule failed is
        // a property of the text itself, so this is what makes the rest of the class meaningful:
        // there is no full stop to look behind and no capital letter to look ahead to.
        assertThat(LONG_BANGLA_PARAGRAPH).doesNotContain(".").doesNotContain("!").doesNotContain("?");
        assertThat(LONG_BANGLA_PARAGRAPH.chars().anyMatch(Character::isUpperCase)).isFalse();
    }

    @Test
    void theDandaEndsASentence() {
        assertThat(Sentences.of(LONG_BANGLA_PARAGRAPH)).hasSize(12);
        // The danda stays with the sentence it terminates, exactly as a full stop does — a chunk
        // that ends mid-punctuation would be a worse boundary than the one being replaced.
        assertThat(Sentences.of(LONG_BANGLA_PARAGRAPH)).allSatisfy(sentence ->
                assertThat(sentence).endsWith("।"));
    }

    @Test
    void aDandaWithNoSpaceAfterItIsStillABoundary() {
        // Bad extraction and tight typesetting both produce this, and unlike a full stop the danda
        // is unambiguous — it is in no number, no abbreviation and no identifier — so it needs no
        // lookahead to protect it.
        assertThat(Sentences.of("প্রথম বাক্য।দ্বিতীয় বাক্য।")).hasSize(2);
        // The double danda, which ends a verse or a section.
        assertThat(Sentences.of("প্রথম বাক্য॥ দ্বিতীয় বাক্য॥")).hasSize(2);
    }

    @Test
    void aFullStopFollowedByBengaliAlsoSplits() {
        // The mixed case: Bangla technical prose that ends a sentence on an English identifier or a
        // complexity class writes a full stop, and what follows it is a caseless letter. This is
        // the one place the English rule was widened, and it cannot affect an English document —
        // no letter used to write English is in the `\p{Lo}` category the lookahead now accepts.
        assertThat(Sentences.of("এর খরচ O(log n). এর কারণ গাছের উচ্চতা।")).hasSize(2);
    }

    @Test
    void englishSentencesSplitExactlyAsTheyDidBefore() {
        // The regression guard for the widened lookahead. Every one of these is a case the original
        // rule was written to get right, and the danda alternative must not have disturbed any of
        // them: a decimal, an abbreviation before a number, an abbreviation before a lowercase
        // word, and an ordinary boundary.
        assertThat(Sentences.of("A skiplist is fast. It uses coin flips.")).hasSize(2);
        assertThat(Sentences.of("The load factor is 0.25 of the table size.")).hasSize(1);
        assertThat(Sentences.of("Fig. 4 shows the expected depth.")).hasSize(1);
        assertThat(Sentences.of("Consider e.g. a treap over the same keys.")).hasSize(1);
        assertThat(Sentences.of("It runs in O(log n) time. (See the appendix.)")).hasSize(2);
    }

    @Test
    void anOversizedBanglaBlockIsCutAtSentencesRatherThanMidWord() {
        // The consequence, through the real ladder. One page, no headings, no embedding provider —
        // so tier 1 finds nothing to split on, tier 2 declines, and tier 3 is what actually places
        // every boundary. Before the danda was a boundary, tier 3 had no sentence rung to step down
        // to here and the whole paragraph was cut on word count.
        assertThat(tokenCounter.count(LONG_BANGLA_PARAGRAPH))
                .as("the fixture has to exceed the ceiling or tier 3 never runs")
                .isGreaterThan(500);

        List<TextChunk> chunks = Chunkers.standard()
                .chunk(List.of(new PageText(1, LONG_BANGLA_PARAGRAPH)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().strip())
                        .as("every boundary should be one the author wrote")
                        .endsWith("।"));
    }
}
