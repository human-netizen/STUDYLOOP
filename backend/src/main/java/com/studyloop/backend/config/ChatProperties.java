package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for the RAG chat model. `provider` is "cohere" for now (the only implementation);
// the Cohere key is shared with embeddings (COHERE_API_KEY). A blank key leaves the client
// "unconfigured" so the chat endpoint returns a clear error instead of crashing at startup.
// `minSimilarity` is the confidence gate: if the best retrieved chunk's cosine similarity falls
// below it (and nothing matched lexically either), chat refuses instead of letting the model
// answer from weak context. 0 disables the gate.
//
// `minRelevance` is the same gate reading a better signal — the cross-encoder's calibrated 0..1
// relevance, once Phase 12.1's rerank stage is on. Both live here because they are one policy with
// two inputs, and which one applies depends on whether anything reranked. 0 disables it.
//
// `intent` is the same gate again, split three ways (Phase 18.3). One threshold over every question
// is an average of two populations that behave differently, and averaging them is what made the
// usable gap so narrow: see the record below.
@ConfigurationProperties(prefix = "studyloop.chat")
public record ChatProperties(String provider, Cohere cohere, double minSimilarity, double minRelevance,
                             Intent intent) {

    public ChatProperties {
        if (intent == null) {
            intent = Intent.defaults();
        }
    }

    public record Cohere(String apiKey, String model) { }

    // Per-intent relevance floors, used in place of minRelevance when the intent stage is on. Any
    // of them left at 0 falls back to minRelevance, so a partly configured block is a partly
    // applied policy rather than an open gate.
    //
    // **All three are measured on the same run, and the interesting part is which one moved.** On
    // the Phase 17 corpus, splitting the sixty-four golden questions by intent gives:
    //
    //   bucket    answerable min   unanswerable max   usable gap
    //   lookup            0.594              0.347        0.247
    //   explain           0.331              0.310        0.021
    //   compare           0.520      (none in the set)        —
    //
    // The flat 0.32 was pinned by the *explain* row, and it was carrying the lookup questions with
    // it: four unanswerable questions — Kruskal, Timsort, union-find, the master theorem — scored
    // 0.26 to 0.35 by retrieving a passage about some other running time, and two of them cleared
    // 0.32 and were answered. A lookup floor anywhere in that 0.247-wide gap refuses all four and
    // costs nothing, because the weakest real lookup question in the set scores 0.594.
    public record Intent(double lookup, double explain, double compare) {

        // The midpoint of the gap above, to three decimals, which leaves ~0.12 of margin on each
        // side. Phase 13 had to pick a number with 0.010 of margin; this one has twelve times that,
        // and the reason is not a better model — it is that the two populations stopped being
        // averaged together.
        private static final double DEFAULT_LOOKUP = 0.47;

        // Unchanged from the corpus-wide gate, because this bucket *is* what that gate was
        // calibrated on: 0.310 below, 0.331 above, 0.021 between them.
        private static final double DEFAULT_EXPLAIN = 0.32;

        // **Deliberately not lower, and this is the plan for 18.3 being contradicted by its own
        // measurement.** The stage was designed on the premise that a comparison question spanning
        // two chapters draws weaker per-chunk scores and is over-refused by a flat threshold. On
        // this corpus it is not: the weakest comparison question scores 0.520, well clear of the
        // gate, and the golden set contains no unanswerable comparison question at all — so there
        // is nothing here to calibrate against and nothing being refused that should not be.
        //
        // The premise is not so much wrong as aimed at the wrong number. A comparison question does
        // fail on this corpus, and it fails on the *second* page it needs, which is a recall
        // problem: the gate only ever reads the top chunk's score, and the top chunk of a
        // comparison question is a perfectly good chunk. Lowering a gate on the strength of an
        // argument the data contradicts would be exactly the kind of unmeasured tuning this whole
        // flag scheme exists to prevent, so the bucket ships at the corpus-wide default and stays
        // configurable for a corpus where the measurement comes out differently.
        private static final double DEFAULT_COMPARE = 0.32;

        public Intent {
            if (lookup <= 0) {
                lookup = DEFAULT_LOOKUP;
            }
            if (explain <= 0) {
                explain = DEFAULT_EXPLAIN;
            }
            if (compare <= 0) {
                compare = DEFAULT_COMPARE;
            }
        }

        public static Intent defaults() {
            return new Intent(0, 0, 0);
        }
    }
}
