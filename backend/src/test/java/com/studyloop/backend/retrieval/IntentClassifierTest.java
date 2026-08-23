package com.studyloop.backend.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 18.3. Every question below is a real one from the golden set, and that is the point of the
// class rather than a convenience: the three thresholds in ChatProperties.Intent were calibrated by
// splitting those sixty-four questions into these buckets and reading the gap in each. If a
// question moves bucket, the number that guards it moves with it and the calibration is silently
// gone — so the classification is pinned here, per question, with the relevance score it was
// calibrated against written beside it.
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    // The four unanswerable questions that reached the model under a flat threshold, and the reason
    // the lookup bucket exists. Each asks for a running time this book never states; each retrieves
    // a passage about some other running time and scores 0.26-0.35 for it.
    @ParameterizedTest
    @CsvSource(textBlock = """
            What is the running time of Kruskal's minimum spanning tree algorithm?
            What is the worst-case running time of Timsort?
            What is the amortized running time of the union-find disjoint set structure with path compression?
            What is the master theorem for solving divide-and-conquer recurrences?
            """)
    void theUnanswerableQuestionsThatSlippedThroughAreAllLookups(String question) {
        assertThat(classifier.classify(question)).isEqualTo(QueryIntent.LOOKUP);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            How many comparisons does merge-sort perform in the worst case?
            How much space does an adjacency matrix use to represent a graph?
            At what minimum occupancy does a LinearHashTable shrink its table?
            What is the expected height of a skiplist containing n elements?
            What is the external memory model and what does it count as the cost of an algorithm?
            """)
    void aClosedQuestionIsALookup(String question) {
        assertThat(classifier.classify(question)).isEqualTo(QueryIntent.LOOKUP);
    }

    // The bucket the corpus-wide threshold was calibrated on, and the one that keeps it. C05 — the
    // first of these — scores 0.331 against a floor of 0.320: the narrowest margin in the set, and
    // the reason nothing here may drift into the lookup bucket.
    @ParameterizedTest
    @CsvSource(textBlock = """
            What goes wrong when a hash function distributes keys badly?
            How does Dijkstra's algorithm compute single-source shortest paths?
            Why does randomly choosing which elements to promote keep a skiplist efficient?
            How can a graph model which university classes conflict with each other?
            How do you build a trie over string keys to support autocomplete?
            Is there a diagram showing which chapters depend on which other chapters?
            """)
    void anOpenQuestionAboutAMechanismIsAnExplanation(String question) {
        assertThat(classifier.classify(question)).isEqualTo(QueryIntent.EXPLAIN);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            How does the expected length of a search path in a treap compare with that in a skiplist?
            Which search structures give worst-case O(log n) bounds rather than expected or amortized ones?
            Two search trees are drawn side by side holding the same keys. How do their shapes differ?
            Why might you prefer a red-black tree over a skiplist or a treap?
            Which two functions are plotted against each other to show that a smaller growth rate wins?
            """)
    void aQuestionHoldingTwoThingsTogetherIsAComparison(String question) {
        assertThat(classifier.classify(question)).isEqualTo(QueryIntent.COMPARE);
    }

    // Precedence, and it is load-bearing. "What is the difference between a worst-case, an
    // amortized and an expected running time?" matches a lookup opener and a comparison marker at
    // once. Read as a lookup it would be held to 0.47; it is a comparison, and comparisons are the
    // bucket with nothing to calibrate against.
    @Test
    void aComparisonBeatsALookupOpenerWhenBothMatch() {
        assertThat(classifier.classify(
                "What is the difference between a worst-case, an amortized and an expected running time?"))
                .isEqualTo(QueryIntent.COMPARE);
    }

    // The bucket with the lower bar is the safe default, so anything unrecognised lands there.
    // Missing a lookup costs nothing; inventing one refuses a real question.
    @Test
    void anythingUnrecognisedFallsToTheLowerBar() {
        assertThat(classifier.classify("treaps")).isEqualTo(QueryIntent.EXPLAIN);
        assertThat(classifier.classify("")).isEqualTo(QueryIntent.EXPLAIN);
        assertThat(classifier.classify(null)).isEqualTo(QueryIntent.EXPLAIN);
    }

    @Test
    void readsTheQuestionRegardlessOfCaseAndSpacing() {
        assertThat(classifier.classify("HOW   MANY  nodes  does  a  skiplist  have?"))
                .isEqualTo(QueryIntent.LOOKUP);
    }
}
