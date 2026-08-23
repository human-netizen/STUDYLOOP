package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 11.3 — one switch per retrieval stage, all off by default.
//
// Every phase after this one claims a retrieval improvement, and the claim is only checkable if
// the two runs it compares differ by a property and nothing else. If turning a stage on means
// editing code, the "before" run no longer exists to be re-measured: the numbers in the report
// become assertions about a build nobody can rebuild. A flag makes the comparison a rerun.
//
// They default off so that landing a stage and enabling it stay separate events. A stage arrives
// with its own eval run showing what it did; until that run exists, the pipeline behaves exactly
// as the 11.1 baseline measured it.
@ConfigurationProperties(prefix = "studyloop.retrieval")
public record RetrievalProperties(Stages stages, Rerank rerank, Trigram trigram, Hyde hyde) {

    public RetrievalProperties {
        // Absent configuration means the baseline pipeline, not a null dereference at first use.
        if (stages == null) {
            stages = Stages.allOff();
        }
        if (rerank == null) {
            rerank = Rerank.defaults();
        }
        if (trigram == null) {
            trigram = Trigram.defaults();
        }
        if (hyde == null) {
            hyde = Hyde.defaults();
        }
    }

    // When the HyDE stage fires, as opposed to whether it may (stages.hyde). Both numbers are
    // trigger conditions rather than quality settings — nothing here changes what the second pass
    // does, only which questions get one.
    public record Hyde(double triggerSimilarity, int shortQueryTerms) {

        // Read off the golden set rather than chosen: on the Phase 17 run the fifty-six answerable
        // questions had top cosines from 0.321 to 0.707 (avg 0.568) and the eight unanswerable ones
        // 0.306 to 0.457. 0.45 fires on eight of the answerable questions and on seven of the
        // eight unanswerable ones — about a question in four — which is the population where a
        // rewrite could plausibly help and, for the same reason, the population where a plausible
        // invented passage is most dangerous. That is the trade this stage is measured on.
        private static final double DEFAULT_TRIGGER_SIMILARITY = 0.45;

        // "Treaps?" is one content word: one chance at whatever vocabulary the book happens to use,
        // whatever the cosine says about it.
        private static final int DEFAULT_SHORT_QUERY_TERMS = 2;

        public Hyde {
            if (triggerSimilarity <= 0) {
                triggerSimilarity = DEFAULT_TRIGGER_SIMILARITY;
            }
            if (shortQueryTerms < 0) {
                shortQueryTerms = DEFAULT_SHORT_QUERY_TERMS;
            }
        }

        public static Hyde defaults() {
            return new Hyde(0, DEFAULT_SHORT_QUERY_TERMS);
        }
    }

    // How the trigram stage runs, as opposed to whether it does (stages.trigram).
    //
    // The cut-off it matches at is deliberately *not* here: `<%` reads
    // `pg_trgm.word_similarity_threshold`, which is set on the connection pool, because the
    // alternative — an explicit `word_similarity(...) >= ?` — is the one formulation the GIN index
    // cannot answer, and it turns a bitmap scan into a sequential one over the whole course.
    public record Trigram(int maxTerms) {

        // Each term is one index scan and one similarity evaluation per surviving candidate, so
        // this is a work ceiling rather than a quality setting. Eight covers every question in the
        // golden set with room to spare; a question with more content words than this gets its
        // longest eight, which are its most distinctive.
        private static final int DEFAULT_MAX_TERMS = 8;

        public Trigram {
            if (maxTerms <= 0) {
                maxTerms = DEFAULT_MAX_TERMS;
            }
        }

        public static Trigram defaults() {
            return new Trigram(0);
        }
    }

    // How the rerank stage is configured, as opposed to whether it runs — that is stages.rerank
    // above. The two are separate because they change for different reasons: the switch moves per
    // eval run, these move when the provider or the budget does.
    public record Rerank(String apiKey, String model, int candidates) {

        private static final String DEFAULT_MODEL = "rerank-v3.5";
        // Deep enough that a passage RRF ranked eleventh can still reach the prompt, shallow enough
        // that the cross-encoder is reading a few dozen passages rather than a course. Also inside
        // the 100 documents Cohere bills as a single search unit, so the depth is free.
        private static final int DEFAULT_CANDIDATES = 30;

        public Rerank {
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (candidates <= 0) {
                candidates = DEFAULT_CANDIDATES;
            }
        }

        public static Rerank defaults() {
            return new Rerank(null, null, 0);
        }
    }

    public record Stages(
            // Phase 12: cross-encoder rerank over an over-retrieved candidate set.
            boolean rerank,
            // Phase 18.2: on a question the first pass answered weakly, rewrite it and invent the
            // passage that would have answered it, then retrieve again with both. Conditional, so
            // this switch decides whether the condition is ever checked, not whether every
            // question pays for a second pass.
            boolean hyde,
            // Phase 18.1: trigram similarity as a second lexical retriever, for the typos that
            // empty the lexeme one.
            boolean trigram,
            // Phase 17.3: page images embedded into the text vector space, fused as a third ranked
            // list, for the figures text extraction cannot see.
            //
            // The query-time half of a pair. Whether a corpus *has* visual chunks is decided at
            // ingest by `studyloop.visual.enabled`; this decides whether they are searched. Kept
            // apart on purpose, because the honest baseline for this stage is the same corpus with
            // the list switched off, and that is only possible if the two are separate switches.
            boolean visual,
            // Phase 18.3: three intent buckets, each with its own confidence threshold, in place
            // of one number for every question. The only stage here that changes what is
            // *refused* rather than what is retrieved, which is why it is switchable at all: it
            // is the one whose regression is a silent answer rather than a worse ranking.
            boolean intent,
            // Phase 14: questions generated per section at ingestion and indexed alongside it.
            //
            // The one flag here that is read at *ingest* rather than at query time, which makes it
            // the one that cannot be A/B'd by a restart: the questions are already inside the text
            // that was embedded, so comparing the two pipelines means re-ingesting the corpus
            // (`-Deval.reset=true`), exactly like the chunking settings. It lives here anyway
            // because the eval report's header is built from these flags, and a second switch that
            // could disagree with this one would let the header describe a corpus that does not
            // exist. What is configured over in `studyloop.chunking.synthetic-queries` is the
            // block's shape, the same split `rerank` already has.
            boolean syntheticQueries
    ) {

        public static Stages allOff() {
            return new Stages(false, false, false, false, false, false);
        }

        // Printed in the header of every eval report. A report that does not say which pipeline
        // produced it cannot be compared with another one, and the flags are that statement.
        public String describe() {
            return "rerank=%s  hyde=%s  trigram=%s  visual=%s  intent=%s  synthetic-queries=%s"
                    .formatted(state(rerank), state(hyde), state(trigram), state(visual),
                            state(intent), state(syntheticQueries));
        }

        public boolean anyEnabled() {
            return rerank || hyde || trigram || visual || intent || syntheticQueries;
        }

        private static String state(boolean enabled) {
            return enabled ? "ON" : "off";
        }
    }
}
