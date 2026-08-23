package com.studyloop.backend.retrieval;

// What a question is asking for, to the extent that changes how confident retrieval has to be
// before answering it (Phase 18.3).
//
// **Three, not seven.** Each of these is a threshold that has to be calibrated against a golden set
// of sixty-four questions; at seven buckets several would hold three or four questions each, and a
// threshold fitted to four questions is a number with a decimal point rather than a measurement.
// Three is the coarsest split that still separates the two failure modes the eval can actually see.
public enum QueryIntent {

    // A closed question with a specific answer: a bound, a count, a name, a definition. "How many
    // comparisons does merge-sort perform in the worst case?"
    //
    // The bucket that gets a *higher* bar, which is the opposite of what the plan for this stage
    // predicted and is what the numbers said. A question of this shape either has its answer
    // written somewhere in the corpus or does not, and there is no half-credit: the four
    // unanswerable golden questions that reached the model were all of this shape — Kruskal,
    // Timsort, union-find, the master theorem — each asking for a running time this book never
    // states, each retrieving a passage about some other running time that reads plausibly enough
    // to score 0.26-0.35.
    LOOKUP,

    // An open question about a mechanism or a reason: "why", "how does", "what goes wrong when".
    // The default, and the bucket the corpus-wide threshold was calibrated on.
    EXPLAIN,

    // A question that needs two things held against each other — "how do their guarantees differ",
    // "rather than", "compare with". The plan expected this bucket to need a *lower* bar, on the
    // grounds that a question spanning two chapters draws weaker per-chunk scores.
    COMPARE
}
