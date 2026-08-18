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
public record RetrievalProperties(Stages stages, Rerank rerank) {

    public RetrievalProperties {
        // Absent configuration means the baseline pipeline, not a null dereference at first use.
        if (stages == null) {
            stages = Stages.allOff();
        }
        if (rerank == null) {
            rerank = Rerank.defaults();
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
            // Phase 18: answer the question first, retrieve with the hypothetical answer.
            boolean hyde,
            // Phase 14: trigram similarity as a third lexical retriever, for typos and morphology.
            boolean trigram,
            // Phase 15: page images embedded by a vision model, for figures text extraction cannot see.
            boolean visual,
            // Phase 19: questions generated per chunk at ingestion and indexed alongside it.
            boolean syntheticQueries
    ) {

        public static Stages allOff() {
            return new Stages(false, false, false, false, false);
        }

        // Printed in the header of every eval report. A report that does not say which pipeline
        // produced it cannot be compared with another one, and the flags are that statement.
        public String describe() {
            return "rerank=%s  hyde=%s  trigram=%s  visual=%s  synthetic-queries=%s"
                    .formatted(state(rerank), state(hyde), state(trigram), state(visual),
                            state(syntheticQueries));
        }

        public boolean anyEnabled() {
            return rerank || hyde || trigram || visual || syntheticQueries;
        }

        private static String state(boolean enabled) {
            return enabled ? "ON" : "off";
        }
    }
}
