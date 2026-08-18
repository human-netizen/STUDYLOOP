package com.studyloop.backend.retrieval.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// No Spring context, no database, no provider — this is arithmetic, so it runs in CI on every
// push. That is the point of splitting it out of the harness: the instrument the whole retrieval
// rebuild is judged by should not itself be measured only when someone remembers to run it.
class RetrievalMetricsTest {

    private static final double TOLERANCE = 1e-6;

    private static List<Double> gains(double... values) {
        return Arrays.stream(values).boxed().toList();
    }

    @Nested
    @DisplayName("Recall@k")
    class Recall {

        @Test
        void countsDistinctRelevantItemsFound() {
            // Two of the three pages the answer spans came back.
            assertThat(RetrievalMetrics.recallAt(gains(1, 0, 1, 0, 0, 0), 3))
                    .isCloseTo(2.0 / 3, within(TOLERANCE));
        }

        @Test
        void isOneWhenEverythingRelevantWasRetrieved() {
            assertThat(RetrievalMetrics.recallAt(gains(1, 1, 0, 0, 0, 0), 2)).isEqualTo(1.0);
        }

        @Test
        void isZeroWhenNothingRelevantWasRetrieved() {
            assertThat(RetrievalMetrics.recallAt(gains(0, 0, 0, 0, 0, 0), 3)).isEqualTo(0.0);
        }

        @Test
        void ignoresPosition() {
            // The whole point of reporting MRR and nDCG alongside: recall cannot tell these apart.
            assertThat(RetrievalMetrics.recallAt(gains(1, 0, 0, 0, 0, 0), 1))
                    .isEqualTo(RetrievalMetrics.recallAt(gains(0, 0, 0, 0, 0, 1), 1));
        }

        @Test
        void clampsRatherThanReportingMoreThanEverything() {
            // Only reachable if the grading rule credited an expected page twice. Clamping keeps
            // the report readable; the assertion documents that > 1.0 is not a legal score.
            assertThat(RetrievalMetrics.recallAt(gains(1, 1, 1), 2)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Reciprocal rank")
    class ReciprocalRank {

        @Test
        void isOneWhenTheFirstResultIsRelevant() {
            assertThat(RetrievalMetrics.reciprocalRank(gains(1, 0, 0, 0, 0, 0))).isEqualTo(1.0);
        }

        @Test
        void fallsWithThePositionOfTheFirstHit() {
            assertThat(RetrievalMetrics.reciprocalRank(gains(0, 1, 0, 0, 0, 0))).isEqualTo(0.5);
            assertThat(RetrievalMetrics.reciprocalRank(gains(0, 0, 1, 0, 0, 0)))
                    .isCloseTo(1.0 / 3, within(TOLERANCE));
            assertThat(RetrievalMetrics.reciprocalRank(gains(0, 0, 0, 1, 0, 0))).isEqualTo(0.25);
        }

        @Test
        void looksOnlyAtTheFirstHit() {
            assertThat(RetrievalMetrics.reciprocalRank(gains(0, 1, 0, 0, 0, 0)))
                    .isEqualTo(RetrievalMetrics.reciprocalRank(gains(0, 1, 1, 1, 1, 1)));
        }

        @Test
        void isZeroWhenNothingRelevantWasRetrieved() {
            assertThat(RetrievalMetrics.reciprocalRank(gains(0, 0, 0, 0, 0, 0))).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("nDCG@k")
    class Ndcg {

        @Test
        void isOneWhenTheOrderingCouldNotHaveBeenBetter() {
            assertThat(RetrievalMetrics.ndcgAt(gains(1, 1, 1, 0, 0, 0), 3)).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        void isOneWhenTheOnlyRelevantItemIsFirst() {
            assertThat(RetrievalMetrics.ndcgAt(gains(1, 0, 0, 0, 0, 0), 1)).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        void penalizesLateHits() {
            double early = RetrievalMetrics.ndcgAt(gains(1, 0, 0, 0, 0, 0), 1);
            double late = RetrievalMetrics.ndcgAt(gains(0, 0, 0, 0, 0, 1), 1);
            assertThat(late).isLessThan(early);
            // 1/log2(7) — the sixth slot is worth about a third of the first.
            assertThat(late).isCloseTo(0.356207, within(1e-5));
        }

        @Test
        void neverExceedsOneWhenFewerItemsAreRelevantThanRetrieved() {
            // Guards the ideal-DCG denominator: if it were computed over k rather than over the
            // number of relevant items, a run that found everything would score below 1.0 and
            // every phase after this would be compared against a moving ceiling.
            assertThat(RetrievalMetrics.ndcgAt(gains(1, 1, 0, 0, 0, 0), 2)).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        void separatesOrderingsThatRecallCallsIdentical() {
            List<Double> front = gains(1, 1, 0, 0, 0, 0);
            List<Double> back = gains(0, 0, 0, 0, 1, 1);
            assertThat(RetrievalMetrics.recallAt(front, 2)).isEqualTo(RetrievalMetrics.recallAt(back, 2));
            assertThat(RetrievalMetrics.ndcgAt(front, 2)).isGreaterThan(RetrievalMetrics.ndcgAt(back, 2));
        }

        @Test
        void isZeroWhenNothingRelevantWasRetrieved() {
            assertThat(RetrievalMetrics.ndcgAt(gains(0, 0, 0, 0, 0, 0), 3)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Unanswerable questions")
    class Unanswerable {

        @Test
        void areRejectedRatherThanScoredAsZero() {
            // Scoring them as 0.0 would make the refusal set read as a retrieval regression, which
            // is exactly the signal the harness needs to keep clean.
            assertThatThrownBy(() -> RetrievalMetrics.recallAt(gains(0, 0, 0), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refusal rate");
            assertThatThrownBy(() -> RetrievalMetrics.ndcgAt(gains(0, 0, 0), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
