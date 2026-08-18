package com.studyloop.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 13 — the chunker's configuration, and the one combination that would contradict itself.
//
// Same reasoning as RetrievalPropertiesTest: absent or nonsensical configuration has to land on the
// pipeline the eval measured, not on a null dereference at the first document somebody uploads.
class ChunkingPropertiesTest {

    @Test
    void absentConfigurationIsTheMeasuredPipeline() {
        ChunkingProperties properties = new ChunkingProperties(0, 0, true, 0, true, true, 0);

        assertThat(properties.maxTokens()).isEqualTo(500);
        assertThat(properties.semanticSentenceLimit()).isEqualTo(400);
        assertThat(properties.expansionMaxTokens()).isEqualTo(1_200);
    }

    @Test
    void aMergeFloorAtOrAboveTheCeilingIsReducedRatherThanObeyed() {
        // The two constraints would contradict: every section would be under the floor, so every
        // section would try to merge with the next one, until the merge broke the ceiling it was
        // merging under. Nothing would ever settle, and the misconfiguration would show up as
        // strangely shaped chunks rather than as an error.
        ChunkingProperties properties = new ChunkingProperties(500, 500, true, 400, true, true, 1_200);

        assertThat(properties.minTokens()).isLessThan(properties.maxTokens());
    }

    @Test
    void explicitValuesAreKept() {
        ChunkingProperties properties = new ChunkingProperties(320, 80, false, 50, false, false, 900);

        assertThat(properties.maxTokens()).isEqualTo(320);
        assertThat(properties.minTokens()).isEqualTo(80);
        assertThat(properties.semantic()).isFalse();
        assertThat(properties.contextHeader()).isFalse();
        assertThat(properties.expandToSection()).isFalse();
        assertThat(properties.expansionMaxTokens()).isEqualTo(900);
    }

    @Test
    void aZeroMergeFloorIsARealSetting() {
        // Zero means "never merge", which is a legitimate thing to ask for when measuring what
        // merging is worth. It must not be replaced by the default the way an invalid value is.
        assertThat(new ChunkingProperties(500, 0, true, 400, true, true, 1_200).minTokens()).isZero();
    }
}
