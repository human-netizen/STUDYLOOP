package com.studyloop.backend.config;

import com.studyloop.backend.config.RetrievalProperties.Rerank;
import com.studyloop.backend.config.RetrievalProperties.Stages;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 11.3's stage flags, and from 12.1 the rerank stage's own settings. What is worth pinning
// down is that the baseline is genuinely the baseline, that a report can state which pipeline
// produced it, and that a half-written configuration falls back to working defaults rather than to
// a candidate pool of zero — which would silently make reranking a no-op.
class RetrievalPropertiesTest {

    @Test
    void everyStageIsOffByDefault() {
        Stages stages = Stages.allOff();
        assertThat(stages.rerank()).isFalse();
        assertThat(stages.hyde()).isFalse();
        assertThat(stages.trigram()).isFalse();
        assertThat(stages.visual()).isFalse();
        assertThat(stages.syntheticQueries()).isFalse();
        assertThat(stages.anyEnabled()).isFalse();
    }

    @Test
    void missingConfigurationMeansTheBaselinePipeline() {
        // Rather than a null dereference the first time a report asks what ran.
        assertThat(new RetrievalProperties(null, null, null, null).stages()).isEqualTo(Stages.allOff());
    }

    @Test
    void anUnconfiguredRerankStageStillHasAModelAndAPool() {
        Rerank rerank = new RetrievalProperties(null, null, null, null).rerank();

        assertThat(rerank.apiKey()).isNull();
        assertThat(rerank.model()).isEqualTo("rerank-v3.5");
        // Zero here would leave the candidate pool at topN, so the stage would call the provider on
        // every question and be able to reorder six chunks — paying for a stage that cannot work.
        assertThat(rerank.candidates()).isEqualTo(30);
    }

    @Test
    void anExplicitCandidatePoolIsKept() {
        assertThat(new Rerank("key", "rerank-v4.0", 50).candidates()).isEqualTo(50);
        assertThat(new Rerank("key", "  ", 50).model()).isEqualTo("rerank-v3.5");
    }

    @Test
    void describesEveryStageSoAReportCanSayWhatProducedIt() {
        String described = Stages.of(true, false, false, false, false, false, false).describe();
        assertThat(described).contains("rerank=ON").contains("hyde=off")
                .contains("trigram=off").contains("lexical-or=off").contains("visual=off")
                .contains("intent=off").contains("synthetic-queries=off")
                // Phase 23.2, and it reads ON because `Stages.of` is the seven-flag shape and the
                // eighth defaults on. The report header has to name it either way: a run is only
                // comparable with another run if the line says which pipeline produced it.
                .contains("taxonomy=ON");
    }

    // Phase 23.2, and this is the regression guard for the defect that broke it. `Stages` is bound
    // by Spring Boot as a **value object**, through its constructor, and value-object binding needs
    // exactly one candidate constructor — a record carrying two is ambiguous, binding declines
    // silently, and every flag reads false. That is not a hypothetical: it happened, and it turned
    // `rerank: true` in application.yml into a reranker that never ran. A static factory is how the
    // other three nested records in that file offer a convenience shape, and this asserts that
    // `Stages` has not grown a second constructor beside its canonical one.
    @Test
    void stagesHasExactlyOneConstructorSoSpringCanBindIt() {
        assertThat(Stages.class.getDeclaredConstructors())
                .as("a second constructor makes value-object binding ambiguous and every flag "
                        + "silently false — use a static factory instead")
                .hasSize(1);
    }

    @Test
    void anyEnabledIsTrueAsSoonAsOneStageIsOn() {
        assertThat(Stages.of(false, false, false, false, false, false, true).anyEnabled()).isTrue();
    }
}
