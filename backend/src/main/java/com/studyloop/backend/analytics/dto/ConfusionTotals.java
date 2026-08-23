package com.studyloop.backend.analytics.dto;

// Course-level counts for the window. distinctAskers is how many separate students asked
// anything — the denominator that turns "31 questions" into a claim about the class rather than
// about one busy student.
public record ConfusionTotals(
        int questionsAsked,
        int ungrounded,
        // ungrounded / questionsAsked, precomputed so every client rounds it the same way.
        double ungroundedRate,
        // How many of those refusals the student then asked from general knowledge (Phase 20.2).
        //
        // **It is a better signal about missing material than the refusal count above it**, and
        // that is the reason the escape hatch is measured rather than merely offered. A refusal
        // means the corpus did not cover the question; an escalation means a student cared enough
        // about the answer to ask for it a second way. The first number includes every off-topic
        // question anybody ever typed into the box, and this one mostly does not.
        int escalatedToGeneral,
        int distinctAskers
) {

    public static ConfusionTotals of(int asked, int ungrounded, int escalated, int askers) {
        return new ConfusionTotals(asked, ungrounded,
                asked == 0 ? 0.0 : (double) ungrounded / asked, escalated, askers);
    }
}
