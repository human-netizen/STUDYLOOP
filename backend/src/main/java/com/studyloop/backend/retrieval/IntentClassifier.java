package com.studyloop.backend.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

// Phase 18.3 — what kind of question this is, decided from its wording and nothing else.
//
// **The plan put this in 18.2's provider call, and it is here instead, which is this phase's one
// deliberate departure.** The reason is that 18.2's call is *conditional* — it runs only when a
// first-pass retrieval came back weak, which is about a quarter of questions — so an intent that
// came out of it would be absent for the other three quarters, and a gate that reads a bucket for
// some questions and a corpus-wide default for the rest is not a policy anyone can calibrate. The
// argument the plan itself makes about HyDE applies here unchanged and more strongly: an LLM call
// in front of every question is a latency budget spent on a decision that a phrase match gets
// right. When the expansion call *does* run it returns an intent too, and that one wins — the
// model refines a decision that has already been made rather than being waited for.
//
// The rules below are matched against the question in lower case, longest-signal-first:
//
//   COMPARE  an explicit pairing marker — "difference between", "differ", "rather than",
//            "compare with", "prefer ... over". Checked first, because "What is the difference
//            between X and Y" also matches a lookup marker and is not a lookup.
//   LOOKUP   an opener that expects a value or a name — "how many", "how much", "at what",
//            "what is the/a/an". Deliberately narrow: an unmatched question falls to EXPLAIN,
//            which carries the lower threshold, so the cost of missing one is nothing and the
//            cost of a false positive is a real question refused.
//   EXPLAIN  everything else, and the default the corpus-wide threshold was calibrated on.
//
// What makes this checkable rather than a guess is that the buckets are graded on the golden set
// with the relevance scores a real run produced — see IntentClassifierTest, which asserts the
// classification of every question the gate's calibration depends on.
@Component
public class IntentClassifier {

    // Ordered by how strongly each phrase implies a comparison rather than by length. "both" is
    // absent on purpose: "both add and remove run in O(1)" is one structure, not two.
    private static final List<String> COMPARE_MARKERS = List.of(
            "difference between", "differences between", "differ from", "differs from",
            "how do their", "how does their", " differ", "compare with", "compare to",
            "compared with", "compared to", "comparison between", "versus", " vs ", " vs.",
            "rather than", "instead of", "as opposed to", "against each other", "side by side",
            "trade-off", "tradeoff", "prefer", "which is better", "advantage of");

    // Openers that ask for a value or a name. "what is the" is the broad one and it is kept
    // because the questions it catches wrongly — "what is the external memory model" — are still
    // questions with a definite answer in a definite place, which is what the bucket is about.
    private static final List<String> LOOKUP_MARKERS = List.of(
            "how many", "how much", "how long", "at what", "what is the", "what is a ",
            "what is an ", "what are the", "what running time", "state the", "define ");

    public QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.EXPLAIN;
        }
        String text = " " + query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        if (containsAny(text, COMPARE_MARKERS)) {
            return QueryIntent.COMPARE;
        }
        if (containsAny(text, LOOKUP_MARKERS)) {
            return QueryIntent.LOOKUP;
        }
        return QueryIntent.EXPLAIN;
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
