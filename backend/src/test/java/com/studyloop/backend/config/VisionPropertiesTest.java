package com.studyloop.backend.config;

import com.studyloop.backend.config.VisionProperties.Thresholds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 15 — the router's configuration, and the values that would switch a signal off by accident.
//
// Same reasoning as ChunkingPropertiesTest and RetrievalPropertiesTest: absent configuration has to
// land on the pipeline that was measured, not on a null the first upload dereferences. This one has
// a second job, because two of these thresholds are ratios and a ratio has values that are not
// merely wrong but meaningless — and a meaningless threshold reads as a working signal that never
// fires, which is the failure Phase 12 named as invisible by construction.
class VisionPropertiesTest {

    @Test
    void absentConfigurationIsTheShippedRouter() {
        VisionProperties properties = VisionProperties.defaults();

        assertThat(properties.model()).isEqualTo("gemini-2.5-flash");
        assertThat(properties.dpi()).isEqualTo(150);
        assertThat(properties.maxPagesPerDocument()).isEqualTo(40);
        assertThat(properties.expectedScripts()).containsExactly("LATIN", "COMMON", "GREEK", "INHERITED");
        assertThat(properties.thresholds().minCharsPerThousandPoints()).isEqualTo(0.5);
        assertThat(properties.thresholds().scannedImageCoverage()).isEqualTo(0.5);
        assertThat(properties.thresholds().figureImageCoverage()).isEqualTo(0.25);
        assertThat(properties.thresholds().maxForeignRatio()).isEqualTo(0.30);
        assertThat(properties.thresholds().maxOrderDisagreement()).isEqualTo(0.35);
    }

    @Test
    void aBlankKeyLeavesTheRouterScoringAndSendingNothing() {
        // The rule the rerank stage and the embedding clients already follow: an installation with
        // no key for an optional provider keeps working rather than failing every request.
        assertThat(VisionProperties.defaults().isConfigured()).isFalse();
        assertThat(new VisionProperties(true, "   ", null, 0, 0, null, null).isConfigured()).isFalse();
        assertThat(new VisionProperties(true, "a-key", null, 0, 0, null, null).isConfigured()).isTrue();
    }

    @Test
    void aRatioThatCanNeverBeReachedIsAMisconfigurationRatherThanAThreshold() {
        // Both of these are shares of a total, so 1.0 means "every character on the page must be
        // foreign" and "every word must have moved" — conditions no real page meets. Left as
        // configured they would read as a live signal that never fires, which is worse than an
        // error because nothing ever says so.
        Thresholds impossible = new Thresholds(0, 0, 0, 0, 0, 1.0, 4.0);

        assertThat(impossible.maxForeignRatio()).isEqualTo(0.30);
        assertThat(impossible.maxOrderDisagreement()).isEqualTo(0.35);
    }

    @Test
    void explicitValuesAreKept() {
        VisionProperties properties = new VisionProperties(true, "a-key", "gemini-3-pro", 220, 8,
                List.of("BENGALI", "COMMON"), new Thresholds(1.5, 0.7, 0.4, 220, 3.0, 0.6, 0.5));

        assertThat(properties.model()).isEqualTo("gemini-3-pro");
        assertThat(properties.dpi()).isEqualTo(220);
        assertThat(properties.maxPagesPerDocument()).isEqualTo(8);
        assertThat(properties.expectedScripts()).containsExactly("BENGALI", "COMMON");
        assertThat(properties.thresholds().minCharsPerThousandPoints()).isEqualTo(1.5);
        assertThat(properties.thresholds().figureCharsPerThousandPoints()).isEqualTo(3.0);
        assertThat(properties.thresholds().maxOrderDisagreement()).isEqualTo(0.5);
    }
}
