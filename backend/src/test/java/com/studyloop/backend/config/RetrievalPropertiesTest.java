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
        String described = new Stages(true, false, false, false, false, false).describe();
        assertThat(described).contains("rerank=ON").contains("hyde=off")
                .contains("trigram=off").contains("visual=off").contains("intent=off")
                .contains("synthetic-queries=off");
    }

    @Test
    void anyEnabledIsTrueAsSoonAsOneStageIsOn() {
        assertThat(new Stages(false, false, false, false, false, true).anyEnabled()).isTrue();
    }
}
