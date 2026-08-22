package com.studyloop.backend.config;

import com.studyloop.backend.config.ChunkingProperties.SyntheticQueries;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 13 — the chunker's configuration, and the one combination that would contradict itself.
//
// Same reasoning as RetrievalPropertiesTest: absent or nonsensical configuration has to land on the
// pipeline the eval measured, not on a null dereference at the first document somebody uploads.
class ChunkingPropertiesTest {

    @Test
    void absentConfigurationIsTheMeasuredPipeline() {
        ChunkingProperties properties = new ChunkingProperties(0, 0, true, 0, true, true, 0, null);

        assertThat(properties.maxTokens()).isEqualTo(500);
        assertThat(properties.semanticSentenceLimit()).isEqualTo(400);
        assertThat(properties.expansionMaxTokens()).isEqualTo(1_200);
        // Phase 14: an absent synthetic-queries block is the shipped shape, not a null the first
        // ingest dereferences. The stage flag decides whether any of it runs.
        assertThat(properties.syntheticQueries().perSection()).isEqualTo(6);
        assertThat(properties.syntheticQueries().batchSize()).isEqualTo(8);
        assertThat(properties.syntheticQueries().share()).isEqualTo(0.20);
    }

    @Test
    void aMergeFloorAtOrAboveTheCeilingIsReducedRatherThanObeyed() {
        // The two constraints would contradict: every section would be under the floor, so every
        // section would try to merge with the next one, until the merge broke the ceiling it was
        // merging under. Nothing would ever settle, and the misconfiguration would show up as
        // strangely shaped chunks rather than as an error.
        ChunkingProperties properties = new ChunkingProperties(500, 500, true, 400, true, true, 1_200, null);

        assertThat(properties.minTokens()).isLessThan(properties.maxTokens());
    }

    @Test
    void explicitValuesAreKept() {
        ChunkingProperties properties = new ChunkingProperties(320, 80, false, 50, false, false, 900, new SyntheticQueries(4, 3, 0.5, false));

        assertThat(properties.maxTokens()).isEqualTo(320);
        assertThat(properties.minTokens()).isEqualTo(80);
        assertThat(properties.semantic()).isFalse();
        assertThat(properties.contextHeader()).isFalse();
        assertThat(properties.expandToSection()).isFalse();
        assertThat(properties.expansionMaxTokens()).isEqualTo(900);
        assertThat(properties.syntheticQueries().perSection()).isEqualTo(4);
        assertThat(properties.syntheticQueries().batchSize()).isEqualTo(3);
        assertThat(properties.syntheticQueries().share()).isEqualTo(0.5);
    }

    @Test
    void aShareThatIsNotAFractionIsNotACap() {
        // At or above 1.0 the block could outweigh the passage it describes, which is the exact
        // dilution the cap exists to prevent — so it is replaced rather than obeyed.
        assertThat(new SyntheticQueries(6, 8, 1.0, false).share()).isEqualTo(0.20);
        assertThat(new SyntheticQueries(6, 8, -0.5, false).share()).isEqualTo(0.20);
        assertThat(new SyntheticQueries(6, 8, 0.35, false).share()).isEqualTo(0.35);
    }

    @Test
    void aZeroMergeFloorIsARealSetting() {
        // Zero means "never merge", which is a legitimate thing to ask for when measuring what
        // merging is worth. It must not be replaced by the default the way an invalid value is.
        assertThat(new ChunkingProperties(500, 0, true, 400, true, true, 1_200, null).minTokens()).isZero();
    }
}
