package com.studyloop.backend.analytics.dto;

// Course-level counts for the window. distinctAskers is how many separate students asked
// anything — the denominator that turns "31 questions" into a claim about the class rather than
// about one busy student.
public record ConfusionTotals(
        int questionsAsked,
        int ungrounded,
        // ungrounded / questionsAsked, precomputed so every client rounds it the same way.
        double ungroundedRate,
        int distinctAskers
) {

    public static ConfusionTotals of(int asked, int ungrounded, int askers) {
        return new ConfusionTotals(asked, ungrounded, asked == 0 ? 0.0 : (double) ungrounded / asked, askers);
    }
}
